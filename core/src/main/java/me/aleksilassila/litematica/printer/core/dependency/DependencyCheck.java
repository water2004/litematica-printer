package me.aleksilassila.litematica.printer.core.dependency;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** A bounded, iterative check of a short-lived construction dependency graph. */
public final class DependencyCheck {
    private DependencyCheck() {}

    public enum Reason {
        READY, MISMATCH, CYCLE, UNKNOWN, LIMIT, BLOCKED
    }

    public record Node<T>(Reason condition, List<T> dependencies) {
        public Node {
            dependencies = List.copyOf(dependencies);
        }
    }

    public record Result<T>(Reason reason, T position) {
        public boolean allowed() { return reason == Reason.READY; }
    }

    private record Frame<T>(T position, Node<T> node, Iterator<T> children) {}

    /** Dependencies precede a node's own condition, so cycles cannot masquerade as missing blocks. */
    public static <T> Result<T> evaluate(T root, int maxNodes, Function<T, Node<T>> read) {
        Set<T> active = new HashSet<>();
        Set<T> finished = new HashSet<>();
        ArrayDeque<Frame<T>> stack = new ArrayDeque<>();
        T next = root;
        int visited = 0;
        while (true) {
            if (next != null) {
                if (active.contains(next)) return new Result<>(Reason.CYCLE, next);
                if (!finished.contains(next)) {
                    if (++visited > maxNodes) return new Result<>(Reason.LIMIT, next);
                    Node<T> node = read.apply(next);
                    active.add(next);
                    stack.push(new Frame<>(next, node, node.dependencies().iterator()));
                }
                next = null;
            }
            if (stack.isEmpty()) return new Result<>(Reason.READY, root);
            Frame<T> frame = stack.peek();
            if (frame.children().hasNext()) {
                next = frame.children().next();
            } else {
                if (frame.node().condition() != Reason.READY) {
                    return new Result<>(frame.node().condition(), frame.position());
                }
                stack.pop();
                active.remove(frame.position());
                finished.add(frame.position());
            }
        }
    }
}
