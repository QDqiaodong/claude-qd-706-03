package com.stone.mill.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.stone.mill.entity.LayoutItem;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/** 模块四：排板方案仓储（Criteria 查询 + CriteriaDelete 清重排前的旧排布）。 */
@Repository
public class LayoutRepository {

    @PersistenceContext
    private EntityManager em;

    public void save(LayoutItem item) {
        if (item.getId() == null) {
            em.persist(item);
        } else {
            em.merge(item);
        }
    }

    /** 某荒料的排布，按锯切顺序号升序。 */
    public List<LayoutItem> listByBlock(Long blockId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<LayoutItem> cq = cb.createQuery(LayoutItem.class);
        Root<LayoutItem> root = cq.from(LayoutItem.class);
        cq.select(root).where(cb.equal(root.get("blockId"), blockId)).orderBy(cb.asc(root.get("seqNo")));
        return em.createQuery(cq).getResultList();
    }

    public long countByBlock(Long blockId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<LayoutItem> root = cq.from(LayoutItem.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("blockId"), blockId));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 重排前清空该荒料的旧排布。 */
    public int deleteByBlock(Long blockId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<LayoutItem> cd = cb.createCriteriaDelete(LayoutItem.class);
        Root<LayoutItem> root = cd.from(LayoutItem.class);
        cd.where(cb.equal(root.get("blockId"), blockId));
        return em.createQuery(cd).executeUpdate();
    }

    /** 全厂平均损耗率（%），首页总览用。 */
    public BigDecimal avgWasteRate() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Double> cq = cb.createQuery(Double.class);
        Root<LayoutItem> root = cq.from(LayoutItem.class);
        Path<BigDecimal> waste = root.get("wasteRate");
        cq.select(cb.avg(waste));
        Double avg = em.createQuery(cq).getSingleResult();
        return avg == null ? BigDecimal.ZERO : BigDecimal.valueOf(avg);
    }
}
