//School of Informatics Xiamen University, GPL-3.0 license
package cn.edu.xmu.javaee.productdemoaop.dao;


import cn.edu.xmu.javaee.core.bean.RequestVariables;
import cn.edu.xmu.javaee.core.exception.BusinessException;
import cn.edu.xmu.javaee.core.infrastructure.RedisUtil;
import cn.edu.xmu.javaee.core.model.ReturnNo;
import cn.edu.xmu.javaee.core.model.UserToken;
import cn.edu.xmu.javaee.core.util.JacksonUtil;
import cn.edu.xmu.javaee.core.util.CloneFactory;
import cn.edu.xmu.javaee.productdemoaop.dao.bo.OnSale;
import cn.edu.xmu.javaee.productdemoaop.dao.bo.Product;
import cn.edu.xmu.javaee.productdemoaop.mapper.GoodsPoMapper;
import cn.edu.xmu.javaee.productdemoaop.mapper.ProductPoMapper;
import cn.edu.xmu.javaee.productdemoaop.mapper.po.GoodsPo;
import cn.edu.xmu.javaee.productdemoaop.mapper.po.ProductPo;

import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

import static cn.edu.xmu.javaee.core.model.Constants.PLATFORM;
/**
 * @author Ming Qiu
 **/
@Repository
@Slf4j
@RequiredArgsConstructor
public class ProductDao{

    private final ProductPoMapper productPoMapper;
    private final OnSaleDao onSaleDao;
    private final GoodsPoMapper goodsPoMapper;
    private final RequestVariables requestVariables;
    private final RedisUtil redisUtil;

    /**
     * 用名称寻找Product对象
     *
     * @param name 名称
     * @return Product对象列表，带关联的Product返回
     */
    public List<Product> retrieveSimpleProductByName(Long shopId, String name) throws BusinessException {
        List<Product> productList = new ArrayList<>();
        List<ProductPo> productPoList;
        Pageable pageable = PageRequest.of(1, 100);
        if (PLATFORM.equals(shopId)){
            productPoList = this.productPoMapper.findByName(name, pageable);
        } else {
            productPoList = this.productPoMapper.findByShopIdAndName(shopId, name, pageable);
        }
        for (ProductPo po : productPoList) {
            Product product = CloneFactory.copy(new Product(), po);
            productList.add(product);
        }
        log.debug("retrieveSimpleProductByName: productList = {}", productList);
        return productList;
    }

    /**
     * 用id对象找Product对象
     *
     * @param shopId 商铺id
     * @param productId 产品id
     * @return Product对象，不关联的Product
     */
    // 目前直链数据库
    // 先查 Redis。如果 Redis 有，直接返回。把查到的 product 写入 Redis
    public Product findSimpleProductById(Long shopId, Long productId) throws BusinessException {
        Product product = null;
        String key = String.format("p_info_%d", productId);
        Serializable serializable = redisUtil.get(key);
        if(serializable != null){
            product = (Product) serializable;
            if (!Objects.equals(shopId, product.getShopId()) && !PLATFORM.equals(shopId)){ // 如果 (查的不是自己的店) 且 (查的人不是平台管理员)
                String[] objects = new String[] {"${product}", productId.toString(), shopId.toString()};
                throw new BusinessException(ReturnNo.RESOURCE_ID_OUTSCOPE, JacksonUtil.toJson(objects));
            }
        }else {
            ProductPo productPo = this.findPoById(shopId, productId);
            product = CloneFactory.copy(new Product(), productPo);
            redisUtil.set(key, product, -1);
        }

        log.debug("findSimpleProductById: product = {}", product);
        return product;
    }

    /**
     * 创建Product对象
     *
     * @param product 传入的Product对象
     * @return 返回对象ReturnObj
     */
    public Product insert(Product product) throws BusinessException {
        // 获取当前用户
        UserToken userToken = this.requestVariables.getUser();
        product.setCreatorId(userToken.getId());
        product.setCreatorName(userToken.getName());
        // 复制product对象到productPo对象
        ProductPo po = CloneFactory.copy(new ProductPo(), product);
        log.debug("insert: po = {}", po);
        ProductPo ret = this.productPoMapper.save(po); // 保存productPo对象到数据库
        return CloneFactory.copy(new Product(), ret); // 类型转换：PO -> BO，并返回
    }

    /**
     * 修改商品信息
     *
     * @param product 传入的product对象
     * @return void
     */
    // 先写库然后删缓存 数据库变了，Redis 里的旧数据就是“脏”的，必须删掉，让下一次查询重新去数据库拉最新的。
    public void update(Product product) throws BusinessException {
        UserToken userToken = this.requestVariables.getUser();
        product.setModifierId(userToken.getId());
        product.setModifierName(userToken.getName());
        log.debug("update:  product = {}",  product);
        ProductPo oldPo = this.findPoById(userToken.getDepartId(), product.getId());
        log.debug("update: oldPo = {}", oldPo);
        ProductPo newPo = CloneFactory.copyNotNull(oldPo, product);
        log.debug("update: newPo = {}", newPo);
        this.productPoMapper.save(newPo);
        String key = String.format("p_info_%d", product.getId());
        redisUtil.del(key);
    }

    /**
     * 删除商品
     *
     * @param id 商品id
     * @return
     */
    // 在 deleteById 之后，必须彻底清理和这个商品相关的所有缓存
    public void delete(Long id) throws BusinessException {
        UserToken userToken = this.requestVariables.getUser();
        this.findPoById(userToken.getDepartId(), id);
        this.productPoMapper.deleteById(id);
        String key = String.format("p_info_%d", id);
        redisUtil.del(key);

        // Clean OnSale cache
        String osKey = String.format("p_os_%d", id);
        Serializable osIds = redisUtil.get(osKey);
        if (osIds != null) {
            List<Long> ids = (List<Long>) osIds;
            if (!ids.isEmpty()) {
                String[] osKeys = ids.stream().map(oid -> String.format("os_info_%d", oid)).toArray(String[]::new);
                redisUtil.del(osKeys);
            }
        }
        redisUtil.del(osKey);

        key = String.format("p_rel_%d", id);
        redisUtil.del(key);
    }

    /**
     * 分开的Entity对象
     * @param shopId 商铺id
     * @param productId 产品id
     * @return
     * @throws BusinessException
     */
    // 目前是先查PO再用PO查关联
    // 依次调用三个findSimpleProductById(基本信息) onSaleDao.getLatestOnSale(拿价格) retrieveOtherProduct(拿关联)
    public Product findById(Long shopId, Long productId) throws BusinessException {
        Product product = this.findSimpleProductById(shopId, productId);
        List<OnSale> latestOnSale = this.onSaleDao.getLatestOnSale(productId);
        product.setOnSaleList(latestOnSale);
        ProductPo productPo = CloneFactory.copy(new ProductPo(), product);
        List<Product> otherProduct = this.retrieveOtherProduct(productPo);
        product.setOtherProduct(otherProduct);
        
        log.debug("findById: product = {}", product);
        return product;
    }

    /**
     *
     * @param shopId 商铺id 为PLATFROM则在全系统寻找，否则在商铺内寻找
     * @param name 名称
     * @return Product对象列表，带关联的Product返回
     */
    // 查库获取PO列表没法直接Redis
    public List<Product> retrieveByName(Long shopId, String name) throws BusinessException {
        List<Product> productList = new ArrayList<>();
        Pageable pageable = PageRequest.of(0, 100);
        List<ProductPo> productPoList;
        if (PLATFORM.equals(shopId)) {
            productPoList = this.productPoMapper.findByName(name, pageable);
        }else{
            productPoList = this.productPoMapper.findByShopIdAndName(shopId, name, pageable);
        }
        for (ProductPo po : productPoList) {
            Product product = this.getFullProduct(po); // 改造成走redis
            productList.add(product);
        }
        log.debug("retrieveByName: productList = {}", productList);
        return productList;
    }

    /**
     * 获得关联的对象
     * @param productPo product po对象
     * @return 关联的Product对象
     * @throws DataAccessException
     */
    private Product getFullProduct(@NotNull ProductPo productPo) throws DataAccessException {
        Product product = CloneFactory.copy(new Product(), productPo);
        log.debug("getFullProduct: product = {}",product);
        // 查最新价格，现状直接调Dao查库
        // 改后会在onsaleDao里加缓存，这里不用改
        List<OnSale> latestOnSale = this.onSaleDao.getLatestOnSale(productPo.getId());
        product.setOnSaleList(latestOnSale);
        // retrieveOtherProduct里加redis
        List<Product> otherProduct = this.retrieveOtherProduct(productPo);
        product.setOtherProduct(otherProduct);
        log.debug("getFullProduct: fullproduct = {}",product);
        return product;
    }

    /**
     * 获得相关的产品对象
     * @param productPo product po对象
     * @return 相关产品对象列表
     * @throws DataAccessException
     */
    // 改造后，从redis查，没有则查库，查完写入redis
    private List<Product> retrieveOtherProduct(@NotNull ProductPo productPo) throws DataAccessException {
        String key = String.format("p_rel_%d", productPo.getId());
        List<Product> productList = new ArrayList<>();
        Serializable serializable = redisUtil.get(key);
        if(serializable != null){
            List<Long> productIds = (List<Long>) serializable;
            if (!productIds.isEmpty()) {
                List<String> keys = productIds.stream().map(id -> String.format("p_info_%d", id)).collect(Collectors.toList());
                List<Object> results = redisUtil.getByList(keys);
                for (int i = 0; i < results.size(); i += 2) {
                    Object obj = results.get(i + 1);
                    if (obj != null) {
                        productList.add((Product) obj);
                    } else {
                        Long id = productIds.get(i / 2);
                        try {
                            Product p = this.findSimpleProductById(PLATFORM, id);
                            if (p != null) {
                                productList.add(p);
                            }
                        } catch (BusinessException e) {
                            log.error("retrieveOtherProduct: id = {}", id, e);
                        }
                    }
                }
            }
        }else {
            List<ProductPo> productPoList;
            List<GoodsPo> goodsPos = this.goodsPoMapper.findByProductId(productPo.getId());
            List<Long> productIds = goodsPos.stream().map(GoodsPo::getRelateProductId).collect(Collectors.toList());
            redisUtil.set(key, (Serializable) productIds, -1);

            if (!productIds.isEmpty()) {
                productPoList = this.productPoMapper.findByIdIn(productIds);
                productList = productPoList.stream().map(po -> CloneFactory.copy(new Product(), po)).collect(Collectors.toList());
                for (Product p : productList) {
                    redisUtil.set(String.format("p_info_%d", p.getId()), p, -1);
                }
            }
        }
        return productList;
    }

    /**
     * 找到po对象，判断对象是否存在以及是否属于本商铺
     * @param shopId 商铺id
     * @param productId 商品id
     * @return RESOURCE_ID_OUTSCOPE, RESOURCE_ID_NOTEXIST
     */

    private ProductPo findPoById(Long shopId, Long productId){
        ProductPo productPo = this.productPoMapper.findById(productId).orElseThrow(() ->
                new BusinessException(ReturnNo.RESOURCE_ID_NOTEXIST, JacksonUtil.toJson(new String[] {"${product}", productId.toString()})));
        log.debug("findPoById: shopId = {}, productPo = {}", shopId, productPo);
        if (!Objects.equals(shopId, productPo.getShopId()) && !PLATFORM.equals(shopId)){
            String[] objects = new String[] {"${product}", productId.toString(), shopId.toString()};
            throw new BusinessException(ReturnNo.RESOURCE_ID_OUTSCOPE, JacksonUtil.toJson(objects));
        }
        return productPo;
    }
}
