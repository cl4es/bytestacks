package org.openjdk;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

/**
 * Parses output of -XX:+TraceBytecodes (available in debug builds) and recreates
 * call stacks in a format that is interoperable with Brendan Gregg's FlameGraph
 * tool, see https://github.com/brendangregg/FlameGraph
 *
 * Usage:
 * java org.openjdk.Bytestacks file [granularity]
 *
 * Granularity defines granularity of the output, measured in number of bytecodes:
 * if less than granularity number of bytecodes are executed in a leaf method, frame
 * is omitted from the output but the bytecodes spent in each omitted leaf is folded
 * into the parent call frame so that the aggregate is always exact. Setting this to
 * 1 will generate an exact representation, even for a tiny program may result in a
 * huge flame graph in the end.
 *
 * Usage example:
 * java -XX:+TraceBytecodes ... > tracebytecodes.out
 * java org.openjdk.Bytestacks tracebytecodes.out 25 > tracebytecodes.stacks
 * perl flamegraph.pl tracebytecodes.stacks > tracebytecodes.svg
 */
public class Bytestacks {

    private static int granularity;

    private static boolean mainThreadOnly = false;

    private static boolean scanUnusedConstants = false;

    private static Map<String, Boolean> unusedConstant = new TreeMap<>();

    public static void main(String ... args) throws IOException {
        OptionParser parser = new OptionParser();
        OptionSpec<String> file = parser.nonOptions("file").ofType(String.class);
        OptionSpec<Integer> granularityOption = parser.accepts("granularity").withOptionalArg().ofType(Integer.class).defaultsTo(25);
        parser.accepts("constants");
        try {
            OptionSet options = parser.parse(args);
            granularity = granularityOption.value(options);
            List<String> files = file.values(options);
            if (files.size() != 1) {
                System.err.println("Specify exactly one file");
                parser.printHelpOn(System.err);
                System.exit(-1);
            }
            final Path path = Paths.get(files.get(0));
            scanUnusedConstants = options.has("constants");
            final Stream<String> lines = Files.lines(path, StandardCharsets.ISO_8859_1);
            StackMachine stackMachine = new StackMachine();
            lines.forEach(line -> stackMachine.process(line));
            if (scanUnusedConstants) {
                System.out.println("Unused constants:");
                unusedConstant.entrySet().stream().forEach(e -> {
                    if (e.getValue()) {
                        String constant = e.getKey();
                        int index = constant.indexOf(".");
                        index = constant.indexOf('/', index) + 1;
                        char c = constant.charAt(index);
                        if ((c == '[' || c == 'L') && constant.indexOf("Ljava/lang/String") != index) {
                            System.out.println(constant.substring(0, index - 1) + " (" + constant.substring(index) + ")");
                        }
                    }
                });
            } else {
                ROOT.print();
            }

        } catch (Exception e) {
            System.err.println(e.getMessage());
            parser.printHelpOn(System.err);
            System.exit(-2);
        }

    }

    static final CallFrame ROOT = new CallFrame("root", null);

    static class StackMachine {
        static String mainThread = null;
        int lineNum = 0;
        HashMap<String, CallFrame> threadStacks = new HashMap<>();
        HashMap<String, CallFrame> previousStacks = new HashMap<>();
        HashMap<String, Boolean> throwStacks = new HashMap<>();
        String previousLine = "";
        CallFrame currentFrame = null;
        String currentThread = null;

        void process(String line) {
            line = line.trim();
            lineNum++;
            boolean blankPrevious = previousLine.isEmpty();
            boolean blankCurrent = line.isEmpty();
            if (blankPrevious && blankCurrent) return;
            if (blankCurrent) {
                if (previousLine.endsWith("return") || previousLine.endsWith("return_register_finalizer") || previousLine.contains("goto")) {
                    currentFrame = currentFrame.parent;
                    threadStacks.put(currentThread, currentFrame);
                    throwStacks.put(currentThread, false);
                } else {
                    if (previousLine.endsWith("throw")) {
                        threadStacks.put(currentThread, currentFrame);
                        throwStacks.put(currentThread, true);
                    }
                }
            } else if (!line.startsWith("[")) {
                // Junk data, e.g., constant strings over multiple lines
                return;
            } else if (blankPrevious) {
                String thread = thread(line);
                if (mainThread == null) { mainThread = thread; }
                if (mainThreadOnly && !mainThread.equals(thread)) { return; }
                if (thread == null) {
                    // may have encountered a blank line in a block, keep currentFrame
                    return;
                } else {
                    currentThread = thread;
                    currentFrame = threadStacks.computeIfAbsent(thread, t -> { previousStacks.put(currentThread, ROOT); return ROOT; });

                    int start = start(line);
                    int end = line.indexOf(')', start);
                    if (start == 0 || end == -1) {
                        return;
                    }
                    String methodName = line.substring(start, end + 1);
                    if (line.regionMatches(start, "  ", 0, 2)) { // Weird case where we continue a method
                        methodName = currentFrame.name;
                    } else if (!Character.isAlphabetic(methodName.codePointAt(0))) {
                        // junk frame, discard
                        return;
                    }
                    if (threwException()) {
                        // last call on the stack was a throw, pop until we're matching
                        CallFrame originalFrame = currentFrame.parent;
                        while (currentFrame.parent != null && !currentFrame.name.equals(methodName)) {
                            currentFrame = currentFrame.parent;
                        }
                        if (currentFrame.parent != null && currentFrame.name.equals(methodName)) {
                            // found it!
                            threadStacks.put(currentThread, currentFrame);
                        } else {
                            // no luck... best effort assign whatever the
                            // thread keeps doing to the parent frame 
                            currentFrame = originalFrame.parent;
                            currentFrame = enterFrame(methodName);
                            threadStacks.put(currentThread, currentFrame);
                        }
                    } else if (!currentFrame.name.equals(methodName)) {
                        CallFrame original = currentFrame;
                        int bang = 2;
                        while (bang-- >= 0 && currentFrame.parent != null && !currentFrame.name.equals(methodName)) {
                            currentFrame = currentFrame.parent;
                        }
                        if (currentFrame.parent != null && currentFrame.name.equals(methodName)) {
                            // found it!
                            threadStacks.put(currentThread, currentFrame);
                        } else {
                            // no luck... best effort assign whatever the
                            // thread keeps doing to the parent frame
                            currentFrame = original;
                            currentFrame = enterFrame(methodName);
                            threadStacks.put(currentThread, currentFrame);
                        }
                    }
                }
            } else {
                currentFrame.bytecodes++;
                if (scanUnusedConstants) {
                    if (line.contains("putstatic")) {
                        String constant = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                        unusedConstant.computeIfAbsent(constant, c -> Boolean.TRUE);
                    } else if (line.contains("getstatic")) {
                        String constant = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                        unusedConstant.put(constant, Boolean.FALSE);
                    }
                }
            }
            previousLine = line;
        }

        private boolean threwException() {
            Boolean value = throwStacks.get(currentThread);
            if (value == null || value == false) {
                return false;
            } else {
                throwStacks.put(currentThread, false);
                return true;
            }

        }

        String thread(String line) {
            int index = line.indexOf("[");
            int end = line.indexOf("]");
            if (index != 0 || end == -1) {
                return null;
            }
            return line.substring(index, end + 1);
        }

        int start(String line) {
            int index = line.indexOf(" ") + 1;
            index = line.indexOf(" ", index) + 1;
            index = line.indexOf(" ", index) + 1;
            return index;
        }

        CallFrame enterFrame(String methodName) {
            if (currentFrame == null) {
                return ROOT;
            }
            return currentFrame.callFrames.computeIfAbsent(methodName, name -> new CallFrame(name, currentFrame));
        }
    }

    static class CallFrame {
        String name;
        long bytecodes;
        CallFrame parent;
        final Map<String, CallFrame> callFrames = new TreeMap<>();

        CallFrame(String name, CallFrame parent) {
            this.name = name;
            this.parent = parent;
        }

        long print() {
            long weight = callFrames.entrySet().stream().mapToLong(e -> e.getValue().print()).sum();
            if (name.equals("root")) { return 0L; }
            weight += bytecodes;
            if (weight < granularity) { return weight; }

            CallFrame p = parent;
            ArrayList<String> frames = new ArrayList<>();
            frames.add(name);
            while (p != null && p.parent != null) {
                frames.add(p.name);
                p = p.parent;
            }
            Collections.reverse(frames);
            System.out.print(frames.stream().collect(Collectors.joining(";")) + " " + weight);
            System.out.print('\n');
            return 0;
        }
    }
}
