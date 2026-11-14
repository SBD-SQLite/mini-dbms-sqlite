package sqlike.qo;

import java.util.List;
import java.util.Optional;
import sqlike.shared.PlanNode;
import sqlike.shared.QoException;

/** Mengisi estimatedRows di tiap node of a bound plan buat ditampilkan PlanPrinter */
final class PlanAnnotator {
    private final CardinalityEstimator estimator;

    PlanAnnotator(CardinalityEstimator estimator) {
        this.estimator = estimator;
    }

    PlanNode annotate(PlanNode n) {
        return estimator.withCatalogCache(() -> annotateCached(n));
    }

    private PlanNode annotateCached(PlanNode n) {
        List<PlanNode> children = n.children().stream().map(this::annotateCached).toList();
        return new PlanNode(n.kind(), children, n.outputColumns(), rows(n),
                n.scan(), n.predicate(), n.projection(), n.join(), n.sort(), n.mutation(), n.createIndex(),
                n.limit(), n.createTable(), n.dropTable());
    }

    /** annotation bersifat informational:  node yg estimator cannot resolve jadi unannotated */
    private Optional<Double> rows(PlanNode n) {
        try {
            return Optional.of((double) estimator.estimateRows(n));
        } catch (QoException e) {
            return Optional.empty();
        }
    }
}
