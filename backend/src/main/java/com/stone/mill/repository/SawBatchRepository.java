package com.stone.mill.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.dto.Queries.SawBatchQuery;
import com.stone.mill.entity.SawBatch;
import com.stone.mill.entity.SawBatchItem;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * 模块八：锯切批次与冻结排板快照仓储。
 *
 * <p>收口用 {@link #lockById} 的 SELECT … FOR UPDATE 悲观行锁串行：
 * 两人同时收口 / 重复点击时第二个事务在行锁上等待，拿到锁后状态已是 CLOSED，
 * 业务层据此返回 409 并回带已收口结果，不会形成第二个有效收口。
 */
@Repository
public class SawBatchRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<SawBatch> findById(Long id) {
        return Optional.ofNullable(em.find(SawBatch.class, id));
    }

    /** 悲观行锁取批次（必须在事务内调用），收口专用。 */
    public SawBatch lockById(Long id) {
        return em.createQuery("SELECT b FROM SawBatch b WHERE b.id = :id", SawBatch.class)
                .setParameter("id", id)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst().orElse(null);
    }

    public void save(SawBatch batch) {
        if (batch.getId() == null) {
            em.persist(batch);
        } else {
            em.merge(batch);
        }
    }

    /** 立即落库，让批次号唯一冲突在业务代码处抛出。 */
    public void flush() {
        em.flush();
    }

    public void saveItem(SawBatchItem item) {
        if (item.getId() == null) {
            em.persist(item);
        } else {
            em.merge(item);
        }
    }

    public List<SawBatchItem> listItems(Long batchId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawBatchItem> cq = cb.createQuery(SawBatchItem.class);
        Root<SawBatchItem> root = cq.from(SawBatchItem.class);
        cq.select(root).where(cb.equal(root.get("batchId"), batchId))
                .orderBy(cb.asc(root.get("seqNo")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public List<SawBatch> listByOrder(Long orderId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawBatch> cq = cb.createQuery(SawBatch.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(root).where(cb.equal(root.get("orderId"), orderId))
                .orderBy(cb.asc(root.get("openedAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    /** 全量批次（工单列表聚合 / 谱系补字段用，工厂场景数据量可控）。 */
    public List<SawBatch> listAll() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawBatch> cq = cb.createQuery(SawBatch.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(root).orderBy(cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public List<SawBatch> listByBlock(Long blockId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawBatch> cq = cb.createQuery(SawBatch.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(root).where(cb.equal(root.get("blockId"), blockId))
                .orderBy(cb.asc(root.get("openedAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public List<SawBatch> search(SawBatchQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawBatch> cq = cb.createQuery(SawBatch.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(root);
        cq.where(predicates(cb, root, query));
        cq.orderBy(orderBy(cb, root, query));
        TypedQuery<SawBatch> typed = em.createQuery(cq);
        typed.setFirstResult(query.offset());
        typed.setMaxResults(query.limit());
        return typed.getResultList();
    }

    public long count(SawBatchQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(cb.count(root));
        cq.where(predicates(cb, root, query));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    public long countByStatus(String status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<SawBatch> root = cq.from(SawBatch.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("status"), status));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<SawBatch> root, SawBatchQuery query) {
        List<Predicate> list = new ArrayList<>();
        if (has(query.getStatus())) {
            list.add(cb.equal(root.get("status"), query.getStatus()));
        }
        if (query.getOrderId() != null) {
            list.add(cb.equal(root.get("orderId"), query.getOrderId()));
        }
        if (query.getBlockId() != null) {
            list.add(cb.equal(root.get("blockId"), query.getBlockId()));
        }
        if (has(query.getTeam())) {
            list.add(cb.equal(root.get("team"), query.getTeam()));
        }
        if (has(query.getKeyword())) {
            String kw = like(query.getKeyword());
            list.add(cb.or(cb.like(root.get("batchNo"), kw), cb.like(root.get("snapOrderNo"), kw)));
        }
        return list.toArray(new Predicate[0]);
    }

    private List<Order> orderBy(CriteriaBuilder cb, Root<SawBatch> root, SawBatchQuery query) {
        String key = query.getOrderBy() == null ? "time" : query.getOrderBy();
        Path<?> path = "code".equals(key) ? root.get("batchNo") : root.get("openedAt");
        Order order = query.isDesc() ? cb.desc(path) : cb.asc(path);
        return List.of(order, cb.desc(root.get("id")));
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        return "%" + value.trim() + "%";
    }
}
