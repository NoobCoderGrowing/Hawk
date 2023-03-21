package hawk.index.service;

import hawk.segment.entity.Goods;

import java.util.List;

public interface GoodsService {
    List<Goods> queryAllGoods();
}
