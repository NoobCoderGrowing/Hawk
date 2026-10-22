package hawk.vector.search;

import hawk.vector.query.VectorCondition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link VectorSearcher#combine} 的单元测试 —— 查询树求值里**唯一有分支逻辑**的部分。
 *
 * <p>为什么不测整棵树：叶子节点要真 ONNX 模型 + 真索引才能跑。把集合运算单拎出来测，
 * 覆盖的就是 AND/OR 的语义与分数规则；叶子到索引那一层由端到端用例覆盖。
 */
class VectorConditionCombineTest {

    /** 按插入顺序构造，便于断言。 */
    private static Map<Long, Float> hits(Object... pkScorePairs) {
        Map<Long, Float> m = new LinkedHashMap<>();
        for (int i = 0; i < pkScorePairs.length; i += 2) {
            m.put(((Number) pkScorePairs[i]).longValue(), ((Number) pkScorePairs[i + 1]).floatValue());
        }
        return m;
    }

    @Test
    void orTakesUnionAndSumsScores() {
        Map<Long, Float> a = hits(1L, 0.9f, 2L, 0.5f);
        Map<Long, Float> b = hits(2L, 0.7f, 3L, 0.6f);

        Map<Long, Float> result = VectorSearcher.combine(VectorCondition.Relation.OR, List.of(a, b));

        assertEquals(3, result.size());
        assertEquals(0.9f, result.get(1L), 1e-6, "只在 a 里 → 取 a 的分数");
        assertEquals(1.2f, result.get(2L), 1e-6, "a、b 都有 → 分数相加（同一模型，余弦同量纲）");
        assertEquals(0.6f, result.get(3L), 1e-6);
    }

    @Test
    void andKeepsOnlyDocsPresentInEveryPart() {
        Map<Long, Float> a = hits(1L, 0.9f, 2L, 0.5f, 3L, 0.4f);
        Map<Long, Float> b = hits(2L, 0.7f, 3L, 0.2f, 4L, 0.8f);

        Map<Long, Float> result = VectorSearcher.combine(VectorCondition.Relation.AND, List.of(a, b));

        assertEquals(2, result.size(), "只有 2、3 同时出现在两边");
        assertEquals(1.2f, result.get(2L), 1e-6);
        assertEquals(0.6f, result.get(3L), 1e-6);
    }

    @Test
    void andWithNoOverlapIsEmpty() {
        Map<Long, Float> result = VectorSearcher.combine(VectorCondition.Relation.AND,
                List.of(hits(1L, 0.9f), hits(2L, 0.8f)));

        assertEquals(0, result.size(), "没有交集就是空 —— 调用方会看到 0 条，而不是某个子句的结果");
    }

    @Test
    void threeWayAndRequiresAllThree() {
        Map<Long, Float> a = hits(1L, 0.9f, 2L, 0.5f);
        Map<Long, Float> b = hits(1L, 0.8f, 2L, 0.4f);
        Map<Long, Float> c = hits(1L, 0.7f);

        Map<Long, Float> result = VectorSearcher.combine(VectorCondition.Relation.AND, List.of(a, b, c));

        assertEquals(1, result.size());
        assertEquals(2.4f, result.get(1L), 1e-6, "三份分数相加 0.9+0.8+0.7");
    }

    @Test
    void emptyPartsYieldEmpty() {
        assertEquals(0, VectorSearcher.combine(VectorCondition.Relation.OR, List.of()).size());
        assertEquals(0, VectorSearcher.combine(VectorCondition.Relation.AND, List.of()).size());
    }

    @Test
    void singlePartIsIdentity() {
        Map<Long, Float> a = hits(1L, 0.9f, 2L, 0.5f);

        assertEquals(a, VectorSearcher.combine(VectorCondition.Relation.OR, List.of(a)));
        assertEquals(a, VectorSearcher.combine(VectorCondition.Relation.AND, List.of(a)));
    }
}
