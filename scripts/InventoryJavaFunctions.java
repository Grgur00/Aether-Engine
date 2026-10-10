import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Lists declared methods/constructors from compiler syntax trees, without type checking. */
public final class InventoryJavaFunctions {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A JDK is required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var files = manager.getJavaFileObjectsFromPaths(Arrays.stream(args).map(Path::of).toList());
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, java.util.List.of("-proc:none"), null, files);
            var trees = Trees.instance(task);
            var positions = trees.getSourcePositions();
            for (var unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    final ArrayDeque<String> owners = new ArrayDeque<>();

                    @Override public Void visitClass(ClassTree node, Void unused) {
                        String name = node.getSimpleName().toString();
                        owners.addLast(name.isEmpty() ? "anonymous" : name);
                        super.visitClass(node, unused);
                        owners.removeLast();
                        return null;
                    }

                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        String name = node.getName().toString();
                        if (name.equals("<init>")) name = owners.getLast();
                        long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, node));
                        String parameters = node.getParameters().toString().replace('\n', ' ').replace('\t', ' ');
                        System.out.printf("%s\t%s\t%s\t%d\t%s%n", unit.getSourceFile().getName(),
                                String.join(".", owners), name, line, parameters);
                        return super.visitMethod(node, unused);
                    }
                }.scan(unit, null);
            }
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    throw new IllegalArgumentException(diagnostic.toString());
                }
            }
        }
    }
}
