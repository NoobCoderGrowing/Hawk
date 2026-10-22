package hawk.cloud.indexer.dataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;


public class MySqlDataSource implements DataSource {

    private final String url;
    private final String username;
    private final String password;
    private final String table;
    private final String shardColumn;
    /** 本节点是第几分片。取模的右半边，由构造方从 {@code shard-num} 解出来。 */
    private final int myShardIndex;
    private final int batchSize;

    public MySqlDataSource(String url, String username, String password,
                           String table, String shardColumn, int myShardIndex, int batchSize) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.table = table;
        this.shardColumn = shardColumn;
        this.myShardIndex = myShardIndex;
        this.batchSize = batchSize;
    }

    @Override
    public int getTotalBatchCount() throws Exception {
        if (batchSize < 1) {
            throw new IllegalStateException("batchSize 未设置（当前为 " + batchSize + "）");
        }
        long rows;
        try (Connection connection = open();
             PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM " + quote(table));
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            rows = rs.getLong(1);
        }
        return (int) ((rows + batchSize - 1) / batchSize);
    }

    @Override
    public List<OriDocument> getBatchDocument(long offset, int batchSize, int shardCount) throws Exception {
        String sql = "SELECT * FROM " + quote(table)
                + " WHERE MOD(" + quote(shardColumn) + ", ?) = ?"
                + " ORDER BY " + quote(shardColumn)
                + " LIMIT ? OFFSET ?";

        List<OriDocument> batch = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, shardCount);
            ps.setInt(2, myShardIndex);
            ps.setInt(3, batchSize);
            ps.setLong(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                while (rs.next()) {
                    OriDocument doc = new OriDocument();
                    for (int i = 1; i <= columns; i++) {
                        // 列名 → 字段名；SQL 类型名 → 数据类型（如 BIGINT / VARCHAR / DECIMAL）。
                        // 值直接用 JDBC 给出的对象（Long / String / BigDecimal …），不做转换。
                        doc.add(meta.getColumnLabel(i), rs.getObject(i), meta.getColumnTypeName(i));
                    }
                    batch.add(doc);
                }
            }
        }
        return batch;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    /** 反引号包住标识符 —— 表名/列名来自配置，这样写能避免它们和 SQL 关键字撞上。 */
    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
