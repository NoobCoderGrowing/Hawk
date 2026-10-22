package hawk.cloud.recall;

import com.alibaba.fastjson.JSON;

import hawk.vector.query.VectorCondition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VectorConditionJson} 的线格式测试。
 *
 * <p>锁的是**对外的 JSON 契约**：字符串简写、text/group 两种节点、以及"不支持的类型必须明确报错"
 * —— 最后这条最重要：{@code range} 如果被静默忽略，调用方会以为数值筛选生效了。
 */
class VectorConditionJsonTest {

    private static Object json(String s) {
        return JSON.parse(s);
    }

    @Test
    void bareStringIsTextOverAllFields() {
        VectorCondition condition = VectorConditionJson.parse("老黄冰糖");

        VectorCondition.Text text = assertInstanceOf(VectorCondition.Text.class, condition);
        assertEquals("老黄冰糖", text.text());
        assertTrue(text.allFields(), "字符串简写 = 搜所有向量字段");
    }

    @Test
    void textNodeAcceptsFields() {
        VectorCondition condition = VectorConditionJson.parse(
                json("{\"type\":\"text\",\"text\":\"保温杯\",\"fields\":[\"title\"]}"));

        VectorCondition.Text text = assertInstanceOf(VectorCondition.Text.class, condition);
        assertEquals(List.of("title"), text.fields());
        assertFalse(text.allFields());
    }

    @Test
    void textNodeWithoutFieldsMeansAll() {
        VectorCondition condition = VectorConditionJson.parse(
                json("{\"type\":\"text\",\"text\":\"保温杯\"}"));

        assertTrue(assertInstanceOf(VectorCondition.Text.class, condition).allFields());
    }

    @Test
    void groupParsesRelationAndChildren() {
        VectorCondition condition = VectorConditionJson.parse(json("""
                {"type":"group","relation":"OR","children":[
                  {"type":"text","text":"a","fields":["title"]},
                  {"type":"text","text":"b","fields":["descript"]}
                ]}"""));

        VectorCondition.Group group = assertInstanceOf(VectorCondition.Group.class, condition);
        assertEquals(VectorCondition.Relation.OR, group.relation());
        assertEquals(2, group.children().size());
    }

    @Test
    void groupAcceptsLowercaseRelation() {
        VectorCondition condition = VectorConditionJson.parse(json("""
                {"type":"group","relation":"and","children":[{"type":"text","text":"a"}]}"""));

        assertEquals(VectorCondition.Relation.AND,
                assertInstanceOf(VectorCondition.Group.class, condition).relation());
    }

    @Test
    void rangeIsRejectedExplicitly() {
        // 这是本测试最重要的一条：不支持就必须明确报错，不能静默变成一个"匹配不到"的空查询
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VectorConditionJson.parse(json(
                        "{\"type\":\"range\",\"field\":\"price\",\"lower\":10,\"upper\":100}")));
        assertTrue(e.getMessage().contains("不支持"), e.getMessage());
        assertTrue(e.getMessage().contains("数值"), e.getMessage());
    }

    @Test
    void unknownTypeIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VectorConditionJson.parse(json("{\"type\":\"nope\",\"text\":\"a\"}")));
        assertTrue(e.getMessage().contains("text / group"), e.getMessage());
    }

    @Test
    void nullAndBlankAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> VectorConditionJson.parse(null));
        assertThrows(IllegalArgumentException.class, () -> VectorConditionJson.parse("   "));
    }

    @Test
    void emptyFieldsArrayIsRejected() {
        // 空数组是歧义的（"搜全部"还是"一个都不搜"），宁可报错也不猜
        assertThrows(IllegalArgumentException.class, () -> VectorConditionJson.parse(
                json("{\"type\":\"text\",\"text\":\"a\",\"fields\":[]}")));
    }

    @Test
    void groupWithoutChildrenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> VectorConditionJson.parse(
                json("{\"type\":\"group\",\"relation\":\"OR\",\"children\":[]}")));
    }

    @Test
    void groupWithoutRelationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> VectorConditionJson.parse(
                json("{\"type\":\"group\",\"children\":[{\"type\":\"text\",\"text\":\"a\"}]}")));
    }

    @Test
    void nestedGroupKeepsPathInErrorMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> VectorConditionJson.parse(json("""
                        {"type":"group","relation":"AND","children":[
                          {"type":"text","text":"ok"},
                          {"type":"range","field":"price"}
                        ]}""")));
        // 嵌套深了必须看得出是第几个子节点错的
        assertTrue(e.getMessage().contains("children[1]"), e.getMessage());
    }

    @Test
    void nonStringNonObjectIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> VectorConditionJson.parse(123));
    }
}
