package org.ngmon.logger.logtranslator.translator;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTree;
import org.ngmon.logger.logtranslator.antlr.JavaBaseListener;
import org.ngmon.logger.logtranslator.antlr.JavaParser;
import org.ngmon.logger.logtranslator.common.*;
import org.ngmon.logger.logtranslator.generator.HelperGenerator;
import org.ngmon.logger.logtranslator.ngmonLogging.LogTranslatorNamespace;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LogTranslator class is a Listener implementation of JavaBaseListener,
 * generated from ANTLR's output, using Java.g4 grammar file.
 * By overriding methods we can change default behaviour of visited nodes/rules
 * when ANTLR walks the given Java file and our specific actions.
 * <p/>
 * This class parses imports, log definitions/declarations, all declared variables, looks
 * for methods/variables, when 'this' java class is extended by other class, sets current
 * logFile
 */
public class LogTranslator extends JavaBaseListener {
    LogTranslatorNamespace LOG = Utils.getLogger();
    TokenStreamRewriter rewriter;
    private LoggerLoader loggerLoader = null;
    private LogFile logFile;
    private String logName = null; // reference to original LOG variable name
    private String logType = null; // reference to original LOG variable type
    private boolean ignoreLogs = false;
    private String classname;

    public LogTranslator(BufferedTokenStream tokens, LogFile logfile, boolean ignoreLogStatements, boolean isExtending) {
        this.ignoreLogs = ignoreLogStatements;
        rewriter = new TokenStreamRewriter(tokens);
        this.logFile = logfile;
        if (isExtending && !ignoreLogStatements) {
            LoggerFactory.setActualLoggingFramework(null);
        }
    }

    public TokenStreamRewriter getRewriter() {
        return rewriter;
    }

    /**
     * Do clean up of resources when exiting given Java source code file.
     *
     * @param ctx ANTLR's internal context of entry point for Java source code
     *            JavaParser.CompilationUnitContext
     */
    @Override
    public void exitCompilationUnit(@NotNull JavaParser.CompilationUnitContext ctx) {
        // do cleanUp()
        LoggerFactory.setActualLoggingFramework(null);
        loggerLoader = null;
        logFile.setFinishedParsing(true);
        Statistics.addProcessedFilesCounter();
    }

    /**
     * If extending class type is not full package qualified name, look for imports to determine package &
     * appropriate file path location to get all variables from that file. (might be already parsed
     * by ANTLR and stored needed variables. - look for isField = true) Else parse file.
     *
     * @param ctx ANTLR's JavaParser.ClassDeclarationContext context
     */
    @Override
    public void enterClassDeclaration(@NotNull JavaParser.ClassDeclarationContext ctx) {
        classname = ctx.Identifier().getText();

        if (ctx.type() != null) {
            String extendingFileTosearch = null;
            boolean isPackage = false;

            // Get class type & look up filepath - based on childCount (number of dots in type) resolve correct type
            switch (ctx.type().classOrInterfaceType().getChildCount()) {
                case 1:
                    // no dots - pure type - has to be in application as source file
                    extendingFileTosearch = ctx.type().getText();
                    break;
                case 2:
                    // Class<DiamondType> - handle same as 3
                case 3:
                    // inner class of some other class - get Main class type
                    extendingFileTosearch = ctx.type().classOrInterfaceType().Identifier(0).getText();
                    break;
                default:
                    // it's a package type - if it is not "our application's namespace" - ignore it
                    if (ctx.type().getText().startsWith(Utils.getApplicationNamespace())) {
                        isPackage = true;
                        extendingFileTosearch = ctx.type().getText();
                    }
            }

            LOG.extending_search_file_isPackage(extendingFileTosearch, isPackage).trace();
            if (extendingFileTosearch != null) {
                // set here current logFile
                LogFile originalLogFile = logFile;
                LogFile extendingLogFile = addExtendingClassVariables(extendingFileTosearch, isPackage);

                //  use it here again
                if (logFile != originalLogFile) {
                    logFile = originalLogFile;
                }

                if (extendingLogFile != null) {
                    logFile.addConnectedLogFilesList(extendingLogFile);
                }

            }
        }
    }

    /**
     * From given extending class type, find appropriate file path to this extending class.
     * Use current import list to determine whole package and then look for files.
     * From import list use only relevant parts - based on application's namespace.
     *
     * @param extendingFileToSearch search for this class type (or package if isPackage is true)
     * @param isPackage             true if extendingFileToSearch Class type is qualified name package
     * @return true if we successfully parsed extending class and added new variables to current logFile
     */
    private LogFile addExtendingClassVariables(String extendingFileToSearch, boolean isPackage) {
        String fileNameFromImport;
        String tempFileImport = null;
        boolean parsedExtendingClass = false;
        LogFile extendingLogFile = null;

        if (extendingFileToSearch.contains("<") && extendingFileToSearch.contains(">")) {
            extendingFileToSearch = extendingFileToSearch.substring(0, extendingFileToSearch.indexOf("<"));
        }

        if (isPackage) {
            tempFileImport = extendingFileToSearch;
        } else {
            for (String fileImport : logFile.getImports()) {
                if (Utils.getQualifiedNameEnd(fileImport).equals(extendingFileToSearch)) {
                    tempFileImport = fileImport;
                    break;
                }
            }
        }

        if (tempFileImport == null) {
            /** Not found in imports && not package itself, it has to be single class type name.
             Class type is definitely from this package, so append logFile's current package name */
            tempFileImport = logFile.getPackageName() + "." + extendingFileToSearch;
        }
        fileNameFromImport = tempFileImport.replaceAll("\\.", File.separator) + ".java";

        Set<LogFile> lfiles = TranslatorStarter.getLogFiles();
        for (LogFile lf : lfiles) {
            if (lf.getFilepath().contains(fileNameFromImport)) {
                if (!logFile.getFilepath().equals(lf.getFilepath())) {
                    if (!lf.isFinishedParsing()) {
                        // parseFile & connect it with this logFile
                        LOG.starting_antlr_on_file(lf.getFilepath(), logFile.getFilepath()).debug();
                        ANTLRRunner.run(lf, false, true);
                        parsedExtendingClass = true;

                    }
                    if (lf.isFinishedParsing() && (!logFile.getFilepath().equals(lf.getFilepath()))) {
                        parsedExtendingClass = true;
                    }
                    extendingLogFile = lf;
                    break;
                }
            }
        }

        if (!parsedExtendingClass) {
            /** We haven't found/added variables from extending class - search from all files. Dig deeper. */
            LOG.not_found_yet_digging_deeper(fileNameFromImport).debug();
            for (String javaFile : LogFilesFinder.getAllJavaFiles()) {
                if (javaFile.contains(fileNameFromImport)) {
                    LOG.found(javaFile).debug();
                    /** if this file is not the same file, go into it, else exit method */
                    if (!logFile.getFilepath().equals(javaFile)) {
                        LogFile nonLogLogFile = new LogFile(javaFile);
                        Statistics.addNonLogLogFile(nonLogLogFile);
                        ANTLRRunner.run(nonLogLogFile, true, true);
                        extendingLogFile = nonLogLogFile;
                        break;
                    }
                }
            }
        }
        return extendingLogFile;
    }

    /**
     * Add logFile's packageName, if this class was not parsed by LogFilesFinder before.
     *
     * @param ctx ANTLR's JavaParser.PackageDeclarationContext context
     */
    @Override
    public void enterPackageDeclaration(@NotNull JavaParser.PackageDeclarationContext ctx) {
        if (logFile.getPackageName() == null) {
            logFile.setPackageName(ctx.qualifiedName().getText());
        }
    }

    /**
     * Method visits all import declarations and stores them.
     * Log type is chose from them. If it contains static import, allow
     * to 'anonymously' add unknown variables.
     *
     * @param ctx ANTLR's context for import declaration
     */
    @Override
    public void exitImportDeclaration(@NotNull JavaParser.ImportDeclarationContext ctx) {
        // Store import classes - might be used later for extending purposes and finding appropriate class
        logFile.addImport(ctx.qualifiedName().getText());
        if (ctx.getText().contains("static")) {
            logFile.setContainsStaticImport(true);
            if (ctx.getText().substring(0, ctx.getText().length() - 1).endsWith("*")) {
                int star = ctx.getText().length() - 3;
                int lastDot = ctx.getText().substring(0, ctx.getText().length() - 4).lastIndexOf(".") + 1;
                String staticImport = ctx.getText().substring(lastDot, star);
                LOG.static_import(staticImport).debug();
                logFile.addStaticImports(staticImport);
            }
        }
    }

    /**
     * Method visits qualified names of import declarations, in case if it is import statement,
     * evaluate it and create new LoggerLoader for this Java source code file.
     * Change import to NGMON's LogFactory, Logger and add namespace import using
     * TokenStreamRewriter class.
     *
     * @param ctx ANTLR's internal context of JavaParser.QualifiedNameContext
     */
    @Override
    public void enterQualifiedName(@NotNull JavaParser.QualifiedNameContext ctx) {
        if (ctx.getParent().getClass() == JavaParser.ImportDeclarationContext.class) {
            /** Determine actual logging framework */
            if (LoggerFactory.getActualLoggingFramework() == null) {
                loggerLoader = LoggerFactory.determineCreateLoggingFramework(ctx.getText());

                if (loggerLoader == null) {
                    /** this is not log import, we can safely skip it */
                LOG.no_logging_framework(LoggerFactory.getActualLoggingFramework(), ctx.getText()).error();
                    return;
                }
            }
            if (loggerLoader != null) {
                /** Change logger factory import */
                if (loggerLoader.getLogFactory() != null) {
                    if (ctx.getText().toLowerCase().contains(loggerLoader.getLogFactory().toLowerCase())) {
                        LOG.loggerloader_logFactory(loggerLoader.getLogFactory(), ctx.getText()).debug();
                        rewriter.replace(ctx.getStart(), ctx.getStop(), Utils.getNgmonLogFactoryImport());
                    }
                }
                /** Change logger and add current log_events namespace and logGlobal imports */
                for (String logImport : loggerLoader.getLogger()) {
                    if (ctx.getText().toLowerCase().equals(logImport.toLowerCase())) {
                        if (getLogType() == null) {
                            logType = ctx.getText();
                            LOG.log_type(logType).debug();
                        }
                        replaceLogImports(ctx);
                    }
                }
            }
        }
    }

    /**
     * Method parses field declaration. It might be any variable
     * or LOG variable. If it is log declaration, rewrite it.
     *
     * @param ctx ANTLR's Field declaration context
     */
    @Override
    public void exitFieldDeclaration(@NotNull JavaParser.FieldDeclarationContext ctx) {
        /** Logger LOG = LogFactory.getLog(TestingClass.class);  ->
         private static final XNamespace LOG = LoggerFactory.getLogger(XNamespace.class); */
        String varName = ctx.variableDeclarators().variableDeclarator(0).variableDeclaratorId().getText();

        // TODO: log names should be in some dictionary form no "log" only
        // Test for equality of Log variable name and type
        if ((varName.toLowerCase().contains("log")) && ctx.type().getText().equals(getLogType())) {
            // store LOG variableName for further easier searching assistance
            if (logName == null) {
                logName = varName;
            }
            replaceLogFactory(ctx);

        } else {
            /** It is not LOG variable, so let's store information about it for further log transformations */
            if (ctx.variableDeclarators().variableDeclarator().size() == 1) {
                logFile.storeVariable(ctx, varName, ctx.type().getText(), true, null);
            } else {
                /** Let's hope there are no 2 loggers defined on same line - should be impossible as well.
                 * If there are 2+ variables defined on single line (with same type) let's handle it.
                 */
                String varType = ctx.type().getText();
                for (JavaParser.VariableDeclaratorContext varContext : ctx.variableDeclarators().variableDeclarator()) {
                    varName = varContext.variableDeclaratorId().getText();
                    logFile.storeVariable(varContext, varName, varType, true, null);
                }
            }
        }
    }

    /**
     * Store one/more local variable declarations to variable list
     *
     * @param ctx ANTLR's JavaParser.LocalVariableDeclarationContext context
     */
    @Override
    public void exitLocalVariableDeclaration(@NotNull JavaParser.LocalVariableDeclarationContext ctx) {
        String varType = ctx.type().getText();

        if (ctx.variableDeclarators().variableDeclarator().size() == 1) {
            String variable = ctx.variableDeclarators().variableDeclarator(0).variableDeclaratorId().getText();
            logFile.storeVariable(ctx, variable, varType, false, null);
        } else {
            /** Multiple variables are defined on one line. Ugly.. handle. */
            List<JavaParser.VariableDeclaratorContext> variables = ctx.variableDeclarators().variableDeclarator();
            for (JavaParser.VariableDeclaratorContext var : variables) {
                String varName = var.variableDeclaratorId().getText();
                logFile.storeVariable(ctx, varName, varType, false, null);
            }
        }
    }

    /**
     * Parse variable names and types from method formal parameters.
     * ANTLR distinguishes by type all and last formal arguments.
     * Last argument has to be passed manually
     *
     * @param ctx ANTLR's JavaParser.FormalParameterListContext context
     */
    @Override
    public void enterFormalParameterList(@NotNull JavaParser.FormalParameterListContext ctx) {
        String varName;
        String varType;
        for (JavaParser.FormalParameterContext parameter : ctx.formalParameter()) {
            varType = parameter.type().getText();
            varName = parameter.variableDeclaratorId().getText();
            logFile.storeVariable(parameter, varName, varType, false, null);
        }
        // manually pass last parameter - uses different context
        if (ctx.lastFormalParameter() != null) {
            JavaParser.LastFormalParameterContext parameter = ctx.lastFormalParameter();
            varType = parameter.type().getText();
            varName = parameter.variableDeclaratorId().getText();
            logFile.storeVariable(parameter, varName, varType, false, null);
        }
    }

    /**
     * Change log definition or store variable.
     * Used in interface, declaration part of static variables.
     *
     * @param ctx ANTLR's context for constant declaration
     */
    @Override
    public void exitConstDeclaration(@NotNull JavaParser.ConstDeclarationContext ctx) {
        if (loggerLoader != null && loggerLoader.containsLogFactory(ctx.getText())) {
            if (this.logName == null) this.logName = ctx.constantDeclarator(0).Identifier().getText();
            replaceLogFactory(ctx);
        } else {
            logFile.storeVariable(ctx, ctx.constantDeclarator(0).Identifier().getText(), ctx.type().getText(), true, null);
        }
    }

    /**
     * Store exception variable and determine type from 'catch clause' statements.
     * If multiple exceptions are declared, use Exception type.
     *
     * @param ctx ANTLR's JavaParser.CatchClauseContext context
     */
    @Override
    public void enterCatchClause(@NotNull JavaParser.CatchClauseContext ctx) {
        String errorVarName = null;
        String errorTypeName;

        if (ctx.getChild(ctx.getChildCount() - 3) != null) {
            errorVarName = ctx.getChild(ctx.getChildCount() - 3).getText();
        }
        /** Check for simple 'catch (Exception e)' or multi-exception
         *  'catch (NullPointerException | IllegalArgumentException | IOException ex)' usage */
        if (ctx.catchType().getChildCount() == 1) {
            errorTypeName = ctx.getChild(2).getText();
        } else {
            /** Store Exception as variable type name (as we can not tell which exception has higher priority) */
            errorTypeName = "Exception";
        }

        logFile.storeVariable(ctx, errorVarName, errorTypeName, false, "Exception");
        LogFile.Variable var = returnLastValue(errorVarName);
    }

    /**
     * Get type and name of variable from enhanced for-loop and store it.
     *
     * @param ctx ANTLR's JavaParser.EnhancedForControlContext context
     */
    @Override
    public void exitEnhancedForControl(@NotNull JavaParser.EnhancedForControlContext ctx) {
        if (ctx.Identifier() != null) {
            logFile.storeVariable(ctx, ctx.Identifier().getText(), ctx.type().getText(), false, null);
        }
    }

    /**
     * Translate log checking method to our static/global checker -
     * LOG.isLevelEnabled() -> LogGlobal.isLevelEnabled()
     *
     * @param ctx ANTLR's JavaParser.BlockStatementContext context
     */
    @Override
    public void exitBlockStatement(@NotNull JavaParser.BlockStatementContext ctx) {
        /** Translate "if (LOG.isXEnabled())" statement to "if (LogGlobal.isXEnabled())" */
        if ((ctx.statement() != null) && (ctx.statement().getChildCount() > 0)) {
            if (ctx.statement().getChild(0).getText().toLowerCase().equals("if")) {
                JavaParser.ExpressionContext exp = ctx.statement().parExpression().expression();
                if (exp.getText().contains(logName + ".")) {
                    if (Utils.listContainsItem(Utils.BOOLEAN_OPERATORS, exp.getText()) != null) {
                        /** if (abc && LOG.isX() || xyz) get LOG statement context */
                        for (JavaParser.ExpressionContext ec : exp.expression()) {
                            if (ec.getText().startsWith(logName + ".")) {
                                exp = ec;
                                break;
                            }
                        }
                    }

                    /** Check if Log call is in current checkerLogMethods() 'isXEnabled()' */
                    ParseTree methodCall;
                    if (exp.start.getText().startsWith(Utils.NEGATION)) {
                        methodCall = exp.expression(0).expression(0).getChild(exp.expression(0).expression(0).getChildCount() - 1);
                    } else {
                        methodCall = exp.expression(0).getChild(exp.expression(0).getChildCount() - 1);

                    }

                    if (loggerLoader.getCheckerLogMethods().contains(methodCall.getText())) {
                        /** Now we can safely replace logName by LogGlobal */
                        JavaParser.ExpressionContext log;
                        if (exp.getText().startsWith(Utils.NEGATION)) {
                            log = exp.expression(0).expression(0).expression(0);
                        } else {
                            log = exp.expression(0).expression(0);
                        }
                        rewriter.replace(log.start, log.stop,
                            Utils.getQualifiedNameEnd(Utils.getNgmonLogGlobal()));
                    } else {
                        LOG.translation_of_log_call_not_implemented(exp.getText()).error();
                        System.err.println("Not implemented translation of log call! " +
                            "Don't know what to do with '" + exp.getText() + "'." + loggerLoader.getCheckerLogMethods());
                    }
                }
            }
        }
    }

    /**
     * When exitting statement expression, check if statement was in
     * LOG.something(something) format. If it was, process it.
     *
     * @param ctx ANTLR's JavaParser.StatementExpressionContext context
     */
    @Override
    public void exitStatementExpression(@NotNull JavaParser.StatementExpressionContext ctx) {
        // Process LOG.XYZ(stuff);
        if ((logName == null) && !ignoreLogs) {
            /**
             * If (extending, visit that class and find log declaration) and use it here.
             * It can be unnecessary hard to find extending class, There might be a chance, that this class
             * extends otherClass, which contains defined LOG. So we will go with 'dummy failsafe logger'
             * which acts as any logger.
             */

            LOG.unableToChangeLogCallsLogFactoryNotDefined(logFile.getFilepath(), ctx.getText()).error();
            /** starts with log followed by ALPHANUM (dot) */
            String logCall = ctx.getText().toLowerCase();
            int end;
            if (logCall.length() < 10) {
                end = logCall.length();
            } else {
                end = 10;
            }
            Matcher matcher = Pattern.compile("log\\w*\\.\\w+").matcher(logCall).region(0, end);

            if (matcher.find()) {
                logName = ctx.expression().expression(0).expression(0).getText();
                if (LoggerFactory.getActualLoggingFramework() == null) {
                    loggerLoader = LoggerFactory.determineCreateLoggingFramework("failsafe");
                }
            }
        }

        if (!ignoreLogs) {
            if (ctx.getText().startsWith(logName + ".")) {
                if ((ctx.expression().expression(0) != null) && (ctx.expression().expression(0).getChildCount() == 3)) {
                    /** Get ".{debug,info,error..}" Log call into methodCall */
                    String methodCall = ctx.expression().expression(0).getChild(2).getText();

                    /** if Log.operation is in currentLoggerMethodList - transform it, generate new stuff... */
                    if (loggerLoader.getTranslateLogMethods().contains(methodCall)) {
                        Log log = transformMethodStatement(ctx.expression().expressionList());
                        log.setOriginalLog(ctx.getText());
                        HelperGenerator.generateMethodName(log, logFile);
                        log.setLevel(methodCall);
                        logFile.addLog(log);
                        replaceLogMethod(ctx, log);

                    }
                }
            }
//        else {
            // ok this is not a LOG statements... we can throw it away
        }
    }

    /**
     * Choose how to transform log statement input, based on logging framework
     * or construction of statement itself. Whether it contains commas, pluses or '{}'.
     *
     * @param expressionList expressionList statement to be evaluated (method_call)
     */
    private Log transformMethodStatement(JavaParser.ExpressionListContext expressionList) {
        Log log = new Log();
        log.setLogFile(logFile);
        boolean formattedLog = false;

        // if expressions are separated by commas and/or first argument contains '%x' or '{}'
        // delicately handle such situation
        if (expressionList != null) {
            String methodText = expressionList.expression(0).getText();
            List<String> stringList = Utils.FORMATTERS;
            String formatterType = Utils.listContainsItem(stringList, expressionList.getText());

            if (formatterType != null) {
                if (log.getFormattingSymbol() == null) {
                    if (formatterType.equals("%")) {
                        if (CommonsLoggerLoader.hasFormatters(methodText)) {
                            setFormattingSymbol(log, formatterType);
                            formattedLog = true;
                        }
                    } else {
                        setFormattingSymbol(log, formatterType);
                        formattedLog = true;
                    }
                }
            }

            /** start evaluating of parsed value from rightmost element, continuing with left sibling */
            List<JavaParser.ExpressionContext> methodList = expressionList.expression();
            Collections.reverse(methodList);
            int counter = 0;
            for (JavaParser.ExpressionContext ec : methodList) {
                if ((formattedLog && counter != methodList.size() - 1) || (formattedLog && ec.getText().contains("String.format"))) {
                    fillCurrentLog(log, ec, true);
                } else {
                    fillCurrentLog(log, ec, false);
                }
                counter++;
            }
        } else {
            // ExpressionList is empty! That means it is 'Log.X()' statement.
            log.setTag("EMPTY_STATEMENT");
        }
        log.cleanUpCommentList();
        return log;
    }

    /**
     * Parse data from expression nodes - recursive tree of expressions in LOG.X(expression) call.
     * Successfully uses ANTLR's property of tree-building, that successive leaves are built on
     * first node, which leaves second node of tree (expression) as log comment or variable.
     *
     * @param log          Log to be filled with data from this log method statement
     * @param expression   ANTLR's internal representation of JavaParser.ExpressionContext context
     * @param formattedVar If true, first argument/expression contains declaration of whole log ( '%s' '{}')
     */
    private void fillCurrentLog(Log log, JavaParser.ExpressionContext expression, boolean formattedVar) {
        if (expression == null) {
            System.err.println("Expression is null");
            return;
        }

        int childCount = expression.getChildCount();
        if (childCount == 1) {
            determineLogTypeAndStore(log, expression, formattedVar);
        } else if (childCount == 2) {
            // 'new Exception()' found only
            LOG.exception("TranslatorException", expression.getText()).debug();
            // new is followed by 'creator context'
            if (expression.getChild(0).getText().equals("new")) {
                determineLogTypeAndStore(log, expression, formattedVar);
            } else {
                System.err.println("Error " + expression.getText());
            }

            /** Recursively call this method to find out more information about *this* statement */
        } else if (childCount == 3) {
            if (expression.expression(1) != null) {
                determineLogTypeAndStore(log, expression.expression(1), formattedVar);
            }
            if (expression.expression().size() <= 1) {
                determineLogTypeAndStore(log, expression, formattedVar);
            } else {
                for (JavaParser.ExpressionContext ec : expression.expression().subList(0, expression.expression().size() - 1)) {
                    fillCurrentLog(log, ec, formattedVar);
                }
            }
        } else if (childCount == 4) {
            if (log.getFormattingSymbol() != null) {
                if (expression.getText().contains("[") && expression.getText().endsWith("]")) {
                    determineLogTypeAndStore(log, expression, true);
                } else {
                    List<JavaParser.ExpressionContext> expList = expression.expressionList().expression();
                    Collections.reverse(expList);
                    for (JavaParser.ExpressionContext ch : expList) {
                        determineLogTypeAndStore(log, ch, true);
                    }
                }
            } else {
                determineLogTypeAndStore(log, expression, formattedVar);
            }
        } else if (childCount == 5) {
            /** ternary operator */
            if (expression.getChild(1).getText().equals("?") && expression.getChild(3).getText().equals(":")) {
                determineLogTypeAndStore(log, expression.expression(0), formattedVar);
            }
        } else {
            System.err.printf("Error! ChildCount=%d: %s %d:%s%n", childCount, expression.getText(), expression.getStart().getLine(), logFile.getFilepath());
        }
    }

    /**
     * Set formatting symbol based on formatter type to given
     * log.
     *
     * @param log           to be added formatting symbol
     * @param formatterType type of found formatter
     */
    private void setFormattingSymbol(Log log, String formatterType) {
        String symbol;
//        FORMATTERS = Arrays.asList("String.format", "MessageFormatter.format",
//                          "StringUtils", "Formatter.format", "print", "formatMessage", "{}", "%");
        switch (formatterType) {
            case "Formatter.format":
            case "String.format":
            case "%":
                symbol = "%";
                break;
            case "MessageFormatter.format":
                symbol = "{0}";
                break;
            case "{}":
                symbol = "{}";
                break;
            case "StringUtils":
                symbol = "";
                break;
            default:
                symbol = "%";
                break;
        }
        log.setFormattingSymbol(symbol);
    }

    /**
     * Method determines type of variable and stores it for this particular logger.
     *
     * @param log        current log instance to store variables for given method
     * @param expression ANTLR's internal representation of JavaParser.ExpressionContext context
     *                   which holds information about variable
     */
    public LogFile.Variable determineLogTypeAndStore(Log log, JavaParser.ExpressionContext expression, boolean formattedVariable) {
        LogFile.Variable varProperty = null;
        if (expression.getText().startsWith("\"")) {
            log.addComment(HelperGenerator.culture(expression.getText()));
        } else {
            varProperty = findVariable(log, expression, formattedVariable);
            log.addVariable(varProperty);
        }
        return varProperty;
    }

    /**
     * Associate input variable with variable from known variables list.
     * This method handles some special cases of 'variable declarations' in log
     * statements. You should create your own, if you find out one.
     * Use ANTLR's grun gui tool to see proper structure.
     * After separating name and type, store variable using storeVariable() method.
     *
     * @param findMe ANTLR's internal representation of JavaParser.ExpressionContext context
     *               which holds variable to find
     */
    private LogFile.Variable findVariable(Log log, JavaParser.ExpressionContext findMe, boolean formattedVar) {
        LOG.lookingForInFile(findMe.getText(), logFile.getFilepath(), findMe.start.getLine()).trace();
        LogFile.Variable foundVar = findVariableInLogFile(logFile, findMe);
        String ngmonNewName = null;
        String findMeText = findMe.getText();
        boolean skipAddingFormattedVar = false;

        if (foundVar == null) {
            String varType;
            String varName;
            String tag;
            /**
             * When variables were not found, dug deeper. Special cases
             * needed to be handled, generated by special log calls
             */

            /** handle google's Joiner class something like python's (zip(map(", ")."stringList")) **/
            if (findMeText.startsWith("Joiner")) {
                varName = findMe.expressionList().expression(0).getText();
                LogFile.Variable var;
                if ((var = findVariableInLogFile(logFile, findMe.expressionList().expression(0))) != null) {
                    foundVar = var;
                } else {
                    varType = "String";
                    logFile.storeVariable(findMe, varName, varType, false, null);
                    foundVar = returnLastValue(varName);
                }
                foundVar.setTag("methodCall");

                /** Hadoop's StringUtils internal function */
            } else if (findMeText.startsWith("StringUtils")) {
                LOG.string_utils(findMeText).debug();
                String replacementText = null;
                if (findMeText.contains("newException")) {
                    int newExcPos = findMeText.indexOf("newException");
                    // add space between "new Exception"
                    replacementText = findMeText.substring(0, newExcPos + 3) + " " + findMeText.substring(newExcPos + 3);
                }
                logFile.storeVariable(findMe, findMeText, "String", false, "StringUtils");
                foundVar = returnLastValue(findMeText);
                foundVar.setTag("methodCall"); // special
                if (replacementText != null) {
                    foundVar.setChangeOriginalName(replacementText);
                }

                /** Handle new Path creation object */
                // findMeText.startsWith("new")
            } else if (findMe.creator() != null && findMeText.contains("Path")) {
                LOG.path(findMeText).debug();
                logFile.storeVariable(findMe, findMeText, "String", false, "newPath");
                foundVar = returnLastValue(findMeText);
                foundVar.setTag("methodCall"); // special

                /** handle new Object[] {} definition in log
                 * We have to store manually all but last variables in actual log.
                 * The last variable will be returned as foundVar and stored during normal workflow. */
            } else if (findMe.creator() != null && findMeText.contains("[]") && findMeText.trim().endsWith("}")) {
                // get variables from new Object[] {var1, var2, var3,...} and store them manually
                List<LogFile.Variable> variableList;
                int i = 0;
                List<JavaParser.VariableInitializerContext> varInitList = findMe.creator().arrayCreatorRest().arrayInitializer().variableInitializer();
                // LOOP from end to start (in reversed order)
                Collections.reverse(varInitList);
                List<LogFile.Variable> reversedFormattedList = new ArrayList<>();
                for (JavaParser.VariableInitializerContext var : varInitList) {
                    if ((variableList = logFile.getVariableList().get(var.getText())) == null) {
                        logFile.storeVariable(var.expression(), var.getText(), "Object", false, HelperGenerator.removeSpecialCharsFromText(var.getText()));
                        foundVar = returnLastValue(var.getText());
                    } else {
                        foundVar = variableList.get(variableList.size() - 1); // get last value
                    }
                    // store manually all but first variable, as we always insert into 0th position.
                    if (i < varInitList.size() - 1) {
                        log.addVariable(foundVar);
                    }
                    reversedFormattedList.add(foundVar);
                    i++;
                }

                if (formattedVar) {
                    for (LogFile.Variable v : reversedFormattedList) {
                        log.addFormattedVariables(v);
                    }
                    skipAddingFormattedVar = true;
                }

                /** handle 'new String(data, UTF_8)' or 'new Exception()' */
            } else if (findMe.creator() != null) {
                // declaration of 'new String(data, ENC)' or 'new Exception()'
                if (findMe.creator().getText().contains("Exception")) {
                    // This _might_ be a problem in future
                    varName = "new " + findMe.creator().getText();
                    varType = "String";
                    ngmonNewName = "exception";
                    tag = "methodCall"; // special

                } else if (findMe.creator().getText().contains("Throwable")) {
                    /** we don't want to have "new Throwable()" as variable -
                     on contrary, set "Error" as ngmon_log_tag */
                    varName = "new " + findMe.creator().getText();
                    varType = "String";
                    ngmonNewName = "throwable";
                    tag = "methodCall"; // special
                } else {
                    varType = findMe.creator().createdName().getText();
                    varName = findMe.creator().classCreatorRest().arguments().expressionList().expression(0).getText();
                    tag = "methodCall"; // special
                }
                logFile.storeVariable(findMe, varName, varType, false, ngmonNewName);
                foundVar = returnLastValue(varName);
                foundVar.setChangeOriginalName(varName + ".toString()");
                foundVar.setTag(tag);

                /** If variable is array [], find declared variable earlier and use it's type.
                 * If type is not found, use String as default. */
            } else if (findMeText.contains("[") && findMeText.contains("]")) {
                varName = findMeText;

                LogFile.Variable var = findVariable(log, findMe.expression(0), formattedVar);
                if (Utils.listContainsItem(Utils.PRIMITIVE_TYPES, var.getType()) != null) {
                    varType = var.getType();
                } else {
                    varType = "String";
                }
                varName = varName.substring(0, varName.indexOf("[")) + varName.substring(varName.indexOf("]") + 1);
                ngmonNewName = HelperGenerator.removeSpecialCharsFromText(varName);
                LOG.storing_array(varName, findMe.expression(0).getText()).debug();
                logFile.storeVariable(findMe, findMeText, varType, false, ngmonNewName);
                foundVar = returnLastValue(findMeText);
                foundVar.setChangeOriginalName(HelperGenerator.addStringTypeCast(findMeText));

                /** if X is instanceof Y,
                 * create a new boolean variable named as 'isInstanceOfY' */
            } else if (findMeText.contains("instanceof")) {
                // Rename variables and set them before ngmon Log itself.  bc instance ofXYZ... => boolean isInstanceOfY = bc;
                // add new parameter to logFile.storeVariable() - newVariableName
                varName = findMe.primary().expression().expression(0).primary().getText() + "Tern";
                varType = "boolean";
                ngmonNewName = "isInstanceOf" + findMe.primary().expression().type().getText();
                logFile.storeVariable(findMe, varName, varType, false, ngmonNewName);
                foundVar = returnLastValue(varName);

                /** Check for ternary if operator in log */
            } else if (findMeText.contains("?")) {
                String tmpVarName;
                String expTrue;
                String expFalse;
                String expBool = null;
                if (findMeText.startsWith("String.format")) {
                    // String.format() get log statement part after formatting string
                    tmpVarName = findMe.expressionList().expression(1).expression(0).expression(0).primary().getText();
                    List<JavaParser.ExpressionContext> eList = findMe.expressionList().expression();
                    Collections.reverse(eList);
                    int i = 0;
                    for (JavaParser.ExpressionContext ec : eList) {
                        if (!ec.getText().startsWith("\"")) {
                            foundVar = findVariable(log, ec, true);
                            if (foundVar == null) {
                                varName = ec.getText();
                                varType = "String";
                                ngmonNewName = HelperGenerator.removeSpecialCharsFromText(varName);
                                logFile.storeVariable(ec, varName, varType, false, ngmonNewName);
                                foundVar = returnLastValue(varName);
                                foundVar.setChangeOriginalName(HelperGenerator.addStringTypeCast(findMeText));

                                if (eList.size() - 2 > i) {
                                    log.addFormattedVariables(foundVar);
                                }
                                i++;
                            }
                        }
                    }
                } else {
                    /**
                     * Create it as "isX",  addComment to log as "isX", store
                     * this boolean variable add tag to this log as "ternary" */
                    if (findMeText.startsWith("(")) {
                        findMe = findMe.primary().expression();
                    }
                    tmpVarName = findMe.expression(0).getText();

                    if (tmpVarName.startsWith("null")) {
                        expBool = findMe.expression(0).expression(1).getText();
                    }
                    expTrue = findMe.expression(1).getText();
                    expFalse = findMe.expression(2).getText();

                    log.setTernaryValues(tmpVarName, expTrue, expFalse, expBool);
                }

                StringBuilder terVarName = new StringBuilder(HelperGenerator.removeSpecialCharsFromText(tmpVarName));
                String operator = Utils.listContainsItem(Utils.BOOLEAN_OPERATORS, terVarName.toString());
                if (operator != null) {
                    terVarName = terVarName.delete(terVarName.indexOf(operator), terVarName.length());
                }
                if (tmpVarName.startsWith("null") && expBool != null) {
                    ngmonNewName = HelperGenerator.removeSpecialCharsFromText(expBool);
                } else {
                    ngmonNewName = "is" + Character.toUpperCase(tmpVarName.charAt(0)) + tmpVarName.substring(1); // varName -> isVarName
                    ngmonNewName = HelperGenerator.removeSpecialCharsFromText(ngmonNewName);
                }
                varType = "String";
                logFile.storeVariable(findMe, terVarName.toString(), varType, false, ngmonNewName);
                foundVar = returnLastValue(terVarName.toString());
                foundVar.setChangeOriginalName(HelperGenerator.addStringTypeCast(findMeText));
                foundVar.setTag("ternary-operator");

                /** parse String.format stuff and add varaibles to formattedList */
            } else if (findMeText.startsWith("String.format")) {
                if (formattedVar) {
                    if (findMe.expression(0).getText().equals("String.format")) {
                        List<JavaParser.ExpressionContext> sfContext = findMe.expressionList().expression();
                        Collections.reverse(sfContext);
                        int i = 0;
                        for (JavaParser.ExpressionContext ec : sfContext) {
                            if (!ec.getText().startsWith("\"")) {
                                foundVar = findVariableInLogFile(logFile, ec);
                                if (foundVar == null) {
                                    varName = ec.getText();
                                    varType = "String";
                                    ngmonNewName = HelperGenerator.removeSpecialCharsFromText(varName);
                                    logFile.storeVariable(ec, varName, varType, false, ngmonNewName);
                                    foundVar = returnLastValue(varName);
                                    foundVar.setChangeOriginalName(HelperGenerator.addStringTypeCast(findMeText));

                                    if (sfContext.size() - 2 > i) {
                                        log.addFormattedVariables(foundVar);
                                    }
                                    i++;
                                } else {
                                    log.addFormattedVariables(foundVar);
                                    skipAddingFormattedVar = true;
                                }
                            }
                        }
                    }
                }

                /** Mathematical expression - use double as type */
            } else if (Utils.listContainsItem(Utils.MATH_OPERATORS, findMeText) != null) {
                /** special case, when it is not a math operation, but variable + variable */
                varType = "double";
                ngmonNewName = "mathExpression";
                varName = findMeText;
                logFile.storeVariable(findMe, varName, varType, false, ngmonNewName);
                foundVar = returnLastValue(varName);
                foundVar.setTag("mathExp");

                /** Handle System.getenv() method **/
            } else if (findMeText.startsWith("System.getenv().get(")) {
                ngmonNewName = findMeText.substring("System.getenv().get(".length() + 1, findMeText.lastIndexOf(")") - 1);
                varType = "String";
                logFile.storeVariable(findMe, findMeText, varType, false, ngmonNewName);
                foundVar = returnLastValue(findMeText);
                foundVar.setTag("methodCall"); // special

                /** Hadoop's Param.toSortedString() ... */
            } else if (findMeText.startsWith("Param.toSortedString(")) {
                varType = "String";
                logFile.storeVariable(findMe, findMeText, varType, false, "parameters");
                foundVar = returnLastValue(findMeText);

                /** Handle special '$(abc)' */
            } else if (findMeText.startsWith("$")) {
                varType = "String";
                ngmonNewName = findMeText.substring(2, findMeText.length() - 1);
                logFile.storeVariable(findMe, findMeText, varType, false, ngmonNewName);
                foundVar = returnLastValue(findMeText);


            } else if (findMeText.startsWith("String.format")) {
                // String.format() get log statement part after formatting string
                logFile.storeVariable(findMe, findMeText, "String", false, "formattedVariable");
                foundVar = returnLastValue(findMeText);

                /**
                 * Handle call of another method in class.
                 * Start another ANTLR process, look for method declarations and return type.
                 * Put it All back here. */
            } else if (findMeText.matches("\\w+\\(.*?\\)")) {
                List<String> methodArgumentsTypeList = new ArrayList<>();
                if (findMe.expressionList() != null) {
                    LogFile.Variable tempList;
                    LOG.formal_parameters(findMe.expressionList().getText()).debug();
                    /** get types of formal parameters for correct method finding */
                    for (JavaParser.ExpressionContext ec : findMe.expressionList().expression()) {
                        if (ec.getText().startsWith("\"") && ec.getText().endsWith("\"")) {
                            methodArgumentsTypeList.add("String");

                        } else if ((tempList = returnLastValue(ec.getText())) != null) {
                            methodArgumentsTypeList.add(tempList.getType());
                        } else {
                            // handle "null" objects
                            methodArgumentsTypeList.add("Object");
                            LOG.unableToDetermineMethodsArgument(ec.getText()).warn();
                        }
                    }
                } else {
                    methodArgumentsTypeList = null;
                }
                /** Look into extending class for this method call */
                LOG.lookingForInFile(findMeText, logFile.getFilepath(), findMe.start.getLine()).debug();
                if (!HelperLogTranslator.findMethod(logFile, findMeText, methodArgumentsTypeList)) {
                    /** Method has not been found in class. Store it anyway.
                     * Exactly same situation as variable containing "." */
                    ngmonNewName = HelperGenerator.removeSpecialCharsFromText(findMe.expression(0).getText()) + "MethodCall";
                    logFile.storeVariable(findMe, findMeText, "String", false, ngmonNewName);
                }
                // found method!
                /** methodCall has been stored like any other 'variable' */
                foundVar = returnLastValue(findMeText);
                foundVar.setTag("methodCall");

                /**  If expression is composite - has at least one '.', use it as "variable" and change type to String
                 * 'l.getLedgerId()', 'KeeperException.create(code,path).getMessage()', ...*/
            } else if (findMeText.contains(".")) {
                StringBuilder newNgmonName = new StringBuilder(findMeText);
                if (newNgmonName.toString().endsWith(".toString()")) {
                    // delete '.toString()'
                    newNgmonName.delete(newNgmonName.lastIndexOf("."), newNgmonName.length());
                    if (newNgmonName.toString().endsWith("()")) {
                        newNgmonName.delete(newNgmonName.length() - 2, newNgmonName.length());
                    }
                    // remove all dots and brackets from methodCall and raise dot-following letter to upper case
                    newNgmonName.replace(0, newNgmonName.length(), HelperGenerator.removeSpecialCharsFromText(newNgmonName.toString()));
                    newNgmonName.append("MethodCall");

                } else {
                    newNgmonName.replace(0, newNgmonName.length(), HelperGenerator.removeSpecialCharsFromText(newNgmonName.toString()));
                }

                logFile.storeVariable(findMe, findMeText, "String", false, newNgmonName.toString());
                foundVar = returnLastValue(findMeText);
                foundVar.setChangeOriginalName(HelperGenerator.addStringTypeCast(findMeText));
                foundVar.setTag("methodCall");


                /** Handle 'this' call */
            } else if (findMeText.startsWith("this")) {
                if (findMeText.equals("this")) {
                    // change first letter to lowercase and use classname as 'this' ngmon's name
                    ngmonNewName = Character.toLowerCase(classname.charAt(0)) + classname.substring(1);
                    logFile.storeVariable(findMe, "this", "String", false, ngmonNewName);
                    foundVar = returnLastValue(findMeText);
                    foundVar.setChangeOriginalName("this.toString()");
                } else {
                    // We can ignore value assignment as we have parsed this.'variable';
                    System.err.println("'this.' call found method!" + findMeText);
                }

                /** If variable begins with NEGATION '!' */
            } else if (findMeText.startsWith("!")) {
                varName = findMeText.substring(1);
                foundVar = returnLastValue(varName);

                /** if whole text is uppercase & we have static imports, assume this is static variable
                 * and store it */
            } else if (logFile.isContainsStaticImport() && findMeText.equals(findMeText.toUpperCase())) {
                LOG.assuming_external_variable_from_static_import(findMeText).debug();
                logFile.storeVariable(findMe, findMeText, "String", false, null);
                foundVar = returnLastValue(findMeText);

                /** variable might be null */
            } else if (findMeText.equals("null")) {
                logFile.storeVariable(findMe, "null", "String", false, "null");
                foundVar = returnLastValue(findMeText); // using this, because of LOG.debug("noteFailure " + exception, null);

                /** 'variable' is true|false statement */
            } else if (findMeText.equals("true") || findMeText.equals("false")) {
                logFile.storeVariable(findMe, findMeText, "boolean", false, "booleanValue");
                foundVar = returnLastValue(findMeText);

                /** type casting (String) var -> var */
            } else if (findMeText.startsWith("(") && (findMeText.indexOf(")") != findMeText.length())) {
                varName = findMeText.substring(findMeText.indexOf(")") + 1).trim();
                varType = findMeText.substring(1, findMeText.indexOf(")")).trim();
                logFile.storeVariable(findMe, varName, varType, false, null);
                foundVar = returnLastValue(varName);
                foundVar.setChangeOriginalName(HelperGenerator.addStringTypeCast(varName));

                /** Last chance - look into extending class and their variables */
            } else if (logFile.getConnectedLogFilesList() != null) {
                for (LogFile lf : logFile.getConnectedLogFilesList()) {
                    foundVar = findVariableInLogFile(lf, findMe);
                }
                if (foundVar != null) {
                    logFile.storeVariable(findMe, foundVar.getName(), foundVar.getType(), foundVar.isField(), foundVar.getNgmonName());
                }

                /** We have ran out of luck. Have not found given variable in my known parsing list. */
            } else {
                System.err.println("Unable to find variable " + findMeText + " in file " +
                    findMe.start.getLine() + " :" + logFile.getFilepath() + "\n" + logFile.getVariableList().keySet());
                if (Utils.ignoreParsingErrors) {
                    return null;
                } else {
                    Thread.dumpStack();
                    System.exit(100);
                }
            }
        }
        if (formattedVar && !skipAddingFormattedVar) {
            log.addFormattedVariables(foundVar);
        }
        return foundVar;
    }

    /**
     * Search for variable in given LogFile's variable list.
     * And / or in connected LogFiles (extending class)
     *
     * @param logFile to search for this variable list
     * @param findMe  variable to look for
     * @return found Variable in given logFile, null if not found
     */
    public LogFile.Variable findVariableInLogFile(LogFile logFile, JavaParser.ExpressionContext findMe) {
        LogFile.Variable foundVar = null;
        boolean isArray = isArray(findMe);
        for (String key : logFile.getVariableList().keySet()) {
            if (findMe.getText().equals(key)) {
                List<LogFile.Variable> variableList = logFile.getVariableList().get(findMe.getText());
                // search for non-array variable value
                if (variableList.size() > 1) {
                    // get closest line number (or field member)
                    int currentLine = 0;
                    int closest = currentLine;
                    for (LogFile.Variable p : variableList) {
                        if (currentLine - p.getLineNumber() < closest) {
                            closest = currentLine - p.getLineNumber();
                            foundVar = p;
                        }
                    }
                } else {
                    foundVar = variableList.get(0);
                }
            } else if (isArray) {
                // look for array[] definition (without any stuff inside)
                if (isArray(key)) {
                    if ((findMe.getChildCount() > 3) && (findMe.expression(1).getText() != null)) {
                        // search for array declaration in variable list - search for %[]
                        if (key.equals(findMe.expression(0).getText() + "[]")) {
                            foundVar = logFile.getVariableList().get(key).get(0);
                        }
                    }
                }
            }
        }

        /** Look into variables from connected (extending class) logFile */
        if (foundVar == null && logFile.getConnectedLogFilesList() != null) {
            // look through all extending classes
            for (LogFile lf : logFile.getConnectedLogFilesList()) {
                if (lf != this.logFile) {
                    // if it is not the same file, get all variableNames and compare them
                    for (String varNameKey : lf.getVariableList().keySet()) {
                        if (findMe.getText().equals(varNameKey)) {
                            // this variable list should contain wanted variable
                            List<LogFile.Variable> vars = lf.getVariableList().get(varNameKey);
                            for (LogFile.Variable v : vars) {
                                if (v.isField()) {
                                    foundVar = v;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return foundVar;
    }

    private boolean isArray(JavaParser.ExpressionContext context) {
        if (context == null) {
            System.out.println("NULL CONTEXT=" + logFile.getFilepath());
            return false;
        } else {
            return (context.getText().contains("[") && context.getText().endsWith("]"));
        }
    }

    private boolean isArray(String text) {
        return (text.contains("[") && text.contains("]"));
    }

    /**
     * Always return last added variable from variable list. This is guaranteed
     * by LinkedHashSet().
     *
     * @param variable to look up for
     * @return returns Variable object for given variable input
     */
    private LogFile.Variable returnLastValue(String variable) {
        LOG.lookingFor(variable).debug();
        List<LogFile.Variable> list = logFile.getVariableList().get(variable);
        if (list == null) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    /**
     * Method returns last part of actual log type from import.
     * Used for searching of declaration of 'old/to be changed' logger
     *
     * @return class name of currently used logger in java file
     */
    public String getLogType() {
        if (logType == null) {
            return null;
        } else {
            // return only last part of QN
            return logType.substring(logType.lastIndexOf(".") + 1);
        }
    }

    /**
     * Method rewrites and adds custom imports in this LogFile ANTLR's run.
     * Rewriting of imports starts from first qualified import name.
     *
     * @param context ANTLR's QualifiedNameContext - imports part
     */
    private void replaceLogImports(JavaParser.QualifiedNameContext context) {
        String namespaceImport = Utils.getNgmongLogEventsImportPrefix() + "." +
            ANTLRRunner.getCurrentFile().getNamespace() + "." +
            ANTLRRunner.getCurrentFile().getNamespaceClass() + ";";
        String logGlobalImport = "import " + Utils.getNgmonLogGlobal();
        String simpleLoggerImport = "import " + Utils.getNgmonSimpleLoggerImport() + ";";
        // Change Log import with Ngmon Log, currentNameSpace and LogGlobal imports
        rewriter.replace(context.start, context.stop, namespaceImport + "\n" +
            simpleLoggerImport + "\n" + logGlobalImport);
    }

    /**
     * Method rewrites LogFactory declaration of current Logging framework.
     *
     * @param ctx ANTLR's current rule context
     */
    private void replaceLogFactory(ParserRuleContext ctx) {
        String nsClass = logFile.getNamespaceClass();
        String logFactoryFieldDeclaration = "/* " + ctx.getText() + " */\n\t\t\t" + nsClass +
            " LOG = LoggerFactory.getLogger(" + nsClass + ".class, new SimpleLogger());";
        LOG.replacing(ctx.getText(), logFactoryFieldDeclaration).trace();
        if (logFactoryFieldDeclaration.contains("null")) {
            System.err.println("logfactory contains null!" + logFactoryFieldDeclaration);
        }
        rewriter.replace(ctx.getStart(), ctx.getStop(), logFactoryFieldDeclaration);
    }

    /**
     * Method rewrites current log method with all parsed variables into NGMON
     * syntax. Method is generated in Log object as 'generatedReplacementLog'.
     *
     * @param ctx ANTLR's JavaParser.StatementExpressionContext context
     * @param log current log instance with generated replacement log method
     */
    private void replaceLogMethod(JavaParser.StatementExpressionContext ctx, Log log) {
        String ngmonLogReplacement = HelperGenerator.generateLogMethod(logName, log);
        LOG.original_replacement_log(log.getOriginalLog(), ngmonLogReplacement).debug();
        String commentedOriginalLog = "/* " + log.getOriginalLog() + " */";
        String spaces = HelperGenerator.generateEmptySpaces(ctx.start.getCharPositionInLine());
        rewriter.replace(ctx.start, ctx.stop, commentedOriginalLog + "\n" + spaces + ngmonLogReplacement);
        Statistics.addChangedLogMethodsCount();
    }
}
