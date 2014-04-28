package org.ngmon.logger.logtranslator.generator;

import org.ngmon.logger.logtranslator.common.Log;
import org.ngmon.logger.logtranslator.common.LogFile;
import org.ngmon.logger.logtranslator.common.Utils;
import org.ngmon.logger.logtranslator.ngmonLogging.LogTranslatorNamespace;

public class HelperGenerator {

    private static LogTranslatorNamespace LOG = Utils.getLogger();


    /**
     * Generate method name from 'comments' list - strings found in given log method call.
     * If comment list and variable list is empty, use autogenerated method name from property file.
     *
     * @param log to generate and set method log from
     */
    public static void generateMethodName(Log log, LogFile lf) {
        if (log.getComments().size() == 0) {
            StringBuilder tempName = new StringBuilder();
            for (LogFile.Variable var : log.getVariables()) {
                if (var != null) {
                    if (var.getNgmonName() != null) {
                        tempName.append(var.getNgmonName());
                    } else {
                        tempName.append(var.getName());
                    }
                } else {
                    System.err.println("NULL VAR=" + log + "\n" + lf.getFilepath());
                }
            }
            tempName = new StringBuilder(culture(tempName.toString()));
            int maxLengthUtils = Utils.getNgmonEmptyLogStatementMethodNameLength();
            int maxLength = (tempName.length() < maxLengthUtils) ? tempName.length() : maxLengthUtils;
            if (tempName.length() > 0) {
                log.setMethodName(tempName.substring(0, maxLength));
            } else {
                log.setMethodName(tempName.substring(0, maxLength) + Utils.getNgmonEmptyLogStatement());
            }
        } else {
            StringBuilder logName = new StringBuilder();
            int counter = 0;
            int logNameLength = Utils.getNgmonLogLength();
            for (String comment : log.getComments()) {
                for (String str : comment.split(" ")) {
                    str = culture(str);
                    if (str.length() > 2) {
                        if (!Utils.BANNED_LIST.contains(str)) {
                            if (counter != 0) {
                                logName.append("_");
                            }
                            logName.append(str);
                            counter++;
                        }
                        if (counter >= logNameLength) break;
                    }
                }
            }

            if (Utils.getNgmonMaxLogLength() < logName.length()) {
                logName = logName.delete(Utils.getNgmonMaxLogLength(), logName.length());
            }

            if (Utils.itemInList(Utils.JAVA_KEYWORDS, logName.toString()) || logName.length() == 0) {
//                System.out.println("logname=" + logName.toString());
                log.setMethodName(Utils.getNgmonEmptyLogStatement());
            } else {
                log.setMethodName(logName.toString());
            }
        }
    }


    /**
     * Generate new log method call which will be replaced by 'original' log method call.
     * This new log method will use NGMON logger. Which is goal of this mini-application.
     *
     * @param logName name of current logger variable (mostly "LOG")
     * @param log     current log to get information from
     * @return log method calling in NGMON's syntax form
     */
    public static String generateLogMethod(String logName, Log log) {
        // TODO/wish - if line is longer then 80 chars, append to newline!
        if (log != null) {
            // generate variables
            StringBuilder vars = new StringBuilder();
            StringBuilder tags = new StringBuilder();

            LOG.variablesInLog(log.getVariables().toString()).trace();
//            System.out.println("\t" + log.getVariables() + "\n\t" + log.getOriginalLog() );
            int j = 0;
            for (LogFile.Variable var : log.getVariables()) {
                if (var != null) {
                    if (var.getChangeOriginalName() == null) {
                        vars.append(var.getName());
                    } else {
                        vars.append(var.getChangeOriginalName());
                    }
                } else {
                    System.err.println("VAR NULL in=" + log.getOriginalLog());
                }
                // Append .toString() if variable is of any other type then NGMON allowed data types
                if (var != null) {
                    if (!Utils.itemInList(Utils.NGMON_ALLOWED_TYPES, var.getType().toLowerCase())) {
                        vars.append(".toString()");
                    }
                } else {
                    System.err.println("NULL VAR in =" + vars + "\n" + log.getOriginalLog() + "\n" + log);
                }

                if (j != log.getVariables().size() - 1) {
                    vars.append(", ");
                }
                j++;
            }

            // generate tags
            if (log.getTag() != null) {
                int tagSize = log.getTag().size();
                if (tagSize == 0) {
                    tags = new StringBuilder();
                } else {
                    for (String tag : log.getTag()) {
                        tags.append(".tag(\"").append(tag).append("\")");
                    }
                }
            }

            String replacementLog = String.format("%s.%s(%s)%s.%s()", logName, log.getMethodName(), vars, tags, log.getLevel());
            LOG.replacementLogOriginalLog(replacementLog, log.getOriginalLog()).trace();
            log.setGeneratedReplacementLog(replacementLog);
            return replacementLog;
        } else {
            return null;
        }
    }

    public static String generateEmptySpaces(int numberOfSpaces) {
        StringBuilder spaces = new StringBuilder();
        while (numberOfSpaces > 0) {
            spaces.append(" ");
            numberOfSpaces--;
        }
        return spaces.toString();
    }

    public static String addStringTypeCast(String typecastMe) {
        if (!typecastMe.endsWith(".toString")) {
            return "String.valueOf(" + typecastMe + ")";
        } else {
            return typecastMe;
        }
    }


    /**
     * Method used for dropping unnecessary symbols in comments.
     * Drop quotes, extra spaces, commas, non-alphanum characters
     * into more fashionable way for later NGMON log method naming generation.
     *
     * @param str string to be changed
     */
    public static String culture(String str) {
//        System.out.print("cultivating  " + str);
        // remove escaped characters and formatters like \n, \t
        for (String escaped : Utils.JAVA_ESCAPE_CHARS) {
            str = str.replaceAll(escaped, "");
        }

        str = str.replaceAll("\\d+", "");   // remove all digits as well
        str = str.replaceAll("%\\w", ""); // remove all single chars
        str = str.replaceAll("\\W", " ").replaceAll("\\s+", " ").trim();
//        System.out.print("  -->" + str + "\n");
        str = str.toLowerCase().trim();
        return str;
    }

    /**
     * Method removes dots from text and upper-cases the following letter after ".".
     * Also removes empty brackets and brackets with content are substituted with "_".
     *
     * @param text to be changed
     * @return text without dots
     */
    public static String removeSpecialCharsFromText(String text) {

        // remove quotations
        text = text.replace("\"", "");

        // remove brackets, empty brackets first
        // array
        text = text.replace("[]", "");
        text = text.replace("[", "_").replace("]", "_").replace("__", "");

        text = text.replace("()", "");
        text = text.replace("(", "_").replace(")", "_").replace("__", "");

        text = text.replace("<", "").replace(">", "");

        if (text.endsWith("_")) {
            text = text.substring(0, text.lastIndexOf("_"));
        }
        if (text.startsWith("_")) {
            text = text.substring(1);
        }
        // remove commas
        text = text.replace(",", "AND");
        // remove dots
        StringBuilder newText = new StringBuilder(text);
        int dotsCount = text.length() - text.replace(".", "").length();
        int dotPos;
        for (int i = 0; i < dotsCount; i++) {
            dotPos = text.indexOf(".");
            newText.deleteCharAt(dotPos);
            newText.setCharAt(dotPos, Character.toUpperCase(newText.charAt(dotPos)));
            text = newText.toString();
        }
        return newText.toString();
    }
}
