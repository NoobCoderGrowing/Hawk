package hawk.segment.entity;
import lombok.Data;

@Data
public class Goods {
    public long goods_id;
    public String goods_name;
    public String category;
    public long category_id;
    public float price;
}
