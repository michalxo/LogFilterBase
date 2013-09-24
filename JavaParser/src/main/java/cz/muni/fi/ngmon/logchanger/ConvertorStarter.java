package cz.muni.fi.ngmon.logchanger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 *
 * @author mtoth
 */
public class ConvertorStarter {

    private static final Logger LOG = Logger.getLogger(ConvertorStarter.class.getCanonicalName());
    private static final String JAVA_CLASS_PATH = System.getProperty("java.class.path");
    private static final String PROJECT_PATH = System.getProperty("user.dir")
            + File.separator + "src" + File.separator + "main" + File.separator + "java" + File.separator;

    private static final StringBuilder classpath = new StringBuilder();

    // Just this one file for now
    private static final String filesToCompile = PROJECT_PATH
            + Example.class.getCanonicalName().replace(".", File.separator) + ".java";
//    private static final String fileToCompile = "/home/mtoth/Desktop/CodeAnalyzer.java";
    private static final String errorOutput = System.getProperty("user.home") + File.separator + "/errors.txt";
    private static final String GENERATED_DIR = System.getProperty("user.home") + File.separator + "generated-sources";

    public static void main(String[] args) {
        try {
            checkCreateDir(GENERATED_DIR);
            compileProcessor();

// change java source file
            classpath.delete(0, classpath.length());
            classpath.append(GENERATED_DIR);
//        classpath.append(File.pathSeparator).append(".").append(File.pathSeparator).append(JAVA_CLASS_PATH);
//            LOG.log(Level.INFO, "CLASSPATH = {0}", classpath);

            Iterable<String> options = Arrays.asList("-cp", classpath.toString(),
                    "-processor", ForceAssertions.class.getCanonicalName(), "-printsource", "-d", GENERATED_DIR);

            StringBuilder javacCommand = new StringBuilder();
            javacCommand.append("javac ");
            for (String s : options) {
                javacCommand.append((s + " "));
            }
            javacCommand.append((filesToCompile + "\n"));
            
            LOG.info(javacCommand.toString());
            
            
            compile(options, Arrays.asList(filesToCompile));
        } catch (IOException e) {
            LOG.severe(e.toString());
        }
    }

    //
    public static void compileProcessor() {
        String processorPath = PROJECT_PATH
                + ForceAssertions.class.getCanonicalName().replace(".", File.separator) + ".java";
//        LOG.log(Level.INFO, "PROCESSOR PATH = {0}", processorPath);
        classpath.append(System.getProperty("java.home")).append("/../lib/tools.jar");

//        Iterable<String> options = Arrays.asList("-cp", classpath.toString(), "-d", JAVA_CLASS_PATH);
        Iterable<String> options = Arrays.asList("-cp", classpath.toString(), "-d", GENERATED_DIR);
        compile(options, Arrays.asList(processorPath));
    }

    //
    public static Boolean compile(Iterable<String> compilerOptions, List<String> compileList) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        MyDiagnosticListener myListener = new MyDiagnosticListener();
        DiagnosticCollector collector = new DiagnosticCollector();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(myListener, null, null);
        Iterable compilationUnits = fileManager.getJavaFileObjectsFromStrings(compileList);
        Boolean compilationResult;

        try {
            Writer out = new FileWriter(errorOutput);
            JavaCompiler.CompilationTask task = compiler.getTask(out, fileManager, collector, compilerOptions, null, compilationUnits);
            compilationResult = task.call();

            List<Diagnostic> diagnostics = collector.getDiagnostics();
            for (Diagnostic d : diagnostics) {
                System.out.println(d.getMessage(null));
            }

            if (compilationResult) {
                LOG.log(Level.INFO, "Compilation of file {0} was SUCCESSFUL", compilationUnits);
            } else {
                LOG.log(Level.INFO, "Compilation of file {0} has FAILED", compilationUnits);
            }
            return compilationResult;

        } catch (IOException ex) {
            Logger.getLogger(JavaTreeWalker.class.getName()).log(Level.SEVERE, null, ex);
        }

        return false;
    }

    public static Boolean checkCreateDir(String path) throws IOException {
        File directory = new File(path);

        if (directory.exists()) {
            if (directory.isDirectory()) {
//              remove everything in directory
                if (directory.list().length != 0)
                    throw new IOException("Remove content of following directory " + directory.toString());
//                LOG.severe("Remove content of following directory " + directory.toString());
            }
        } else {
            directory.mkdir();
        }

        return false;
    }

    static class MyDiagnosticListener implements DiagnosticListener {

        @Override
        public void report(Diagnostic diagnostic) {
            System.out.println("Code->" + diagnostic.getCode());
            System.out.println("Column Number->" + diagnostic.getColumnNumber());
            System.out.println("End Position->" + diagnostic.getEndPosition());
            System.out.println("Kind->" + diagnostic.getKind());
            System.out.println("Line Number->" + diagnostic.getLineNumber());
            System.out.println("Message->" + diagnostic.getMessage(Locale.ENGLISH));
            System.out.println("Position->" + diagnostic.getPosition());
            System.out.println("Source" + diagnostic.getSource());
            System.out.println("Start Position->" + diagnostic.getStartPosition());
            System.out.println("\n");
        }
    }
}
