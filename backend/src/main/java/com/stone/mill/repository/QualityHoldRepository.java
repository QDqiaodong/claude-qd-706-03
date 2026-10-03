package com.stone.mill.repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.dto.Queries.QualityHoldQuery;
import com.stone.mill.entity.QualityEvent;
import com.stone.mill.entity.QualityHold;

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
 * 模块六/七：质量隔离记录与流转留痕仓储。
 *
 * <p>并发唯一性不靠应用层先查后插（那在两个并发事务下都会通过），而是靠库里的
 * 部分唯一索引 uk_hold_active_slab：两个事务同时为同一 slab 插入未收口记录时，
 * 后提交者会撞唯一键，由 service 转成 409 冲突并回带上当前记录。
 */
@Repository
public class QualityHoldRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<QualityHold> findById(Long id) {
        return Optional.ofNullable(em.find(QualityHold.class, id));
    }

    public void save(QualityHold hold) {
        if (hold.getId() == null) {
            em.persist(hold);
        } else {
            em.merge(hold);
        }
    }

    /** 立即落库，让唯一索引 / 乐观锁冲突在当前业务代码处抛出，而不是拖到事务提交。 */
    public void flush() {
        em.flush();
    }

    public void saveEvent(QualityEvent event) {
        if (event.getId() == null) {
            em.persist(event);
        } else {
            em.merge(event);
        }
    }

    /** 当前未收口（隔离中 / 待复检）记录，按建立时间取最新的一条。 */
    public QualityHold findActiveBySlab(Long slabId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<QualityHold> cq = cb.createQuery(QualityHold.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(root).where(
                cb.equal(root.get("slabId"), slabId),
                root.get("status").in(com.stone.mill.dto.StoneDict.HOLD_ACTIVE_STATUS))
                .orderBy(cb.desc(root.get("createdAt")));
        return em.createQuery(cq).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }

    /** 一批大板的当前未收口记录，给大板列表 / 发货明细批量补质量状态用。 */
    public List<QualityHold> findActiveBySlabIds(List<Long> slabIds) {
        if (slabIds == null || slabIds.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<QualityHold> cq = cb.createQuery(QualityHold.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(root).where(
                root.get("slabId").in(slabIds),
                root.get("status").in(com.stone.mill.dto.StoneDict.HOLD_ACTIVE_STATUS));
        return em.createQuery(cq).getResultList();
    }

    /** 全部未收口记录（发货派生冻结时全量用，数据量在工厂场景可控）。 */
    public List<QualityHold> findAllActive() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<QualityHold> cq = cb.createQuery(QualityHold.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(root).where(root.get("status").in(com.stone.mill.dto.StoneDict.HOLD_ACTIVE_STATUS));
        return em.createQuery(cq).getResultList();
    }

    /** 某大板的隔离轮次（含前序已放行 / 已报废轮），按轮次升序。 */
    public List<QualityHold> findRoundsBySlab(Long slabId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<QualityHold> cq = cb.createQuery(QualityHold.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(root).where(cb.equal(root.get("slabId"), slabId))
                .orderBy(cb.asc(root.get("roundNo")));
        return em.createQuery(cq).getResultList();
    }

    /** 该大板已经发生过几轮隔离（用于算下一轮 roundNo）。 */
    public long countBySlab(Long slabId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("slabId"), slabId));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 留痕时间线：只追加，按发生时间 / id 升序读出。 */
    public List<QualityEvent> listEvents(Long holdId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<QualityEvent> cq = cb.createQuery(QualityEvent.class);
        Root<QualityEvent> root = cq.from(QualityEvent.class);
        cq.select(root).where(cb.equal(root.get("holdId"), holdId))
                .orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public List<QualityHold> search(QualityHoldQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<QualityHold> cq = cb.createQuery(QualityHold.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(root);
        cq.where(predicates(cb, root, query));
        cq.orderBy(orderBy(cb, root, query));

        TypedQuery<QualityHold> typed = em.createQuery(cq);
        typed.setFirstResult(query.offset());
        typed.setMaxResults(query.limit());
        return typed.getResultList();
    }

    public long count(QualityHoldQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(cb.count(root));
        cq.where(predicates(cb, root, query));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    public long countByStatus(String status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("status"), status));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    public LocalDateTime latestCreatedAt() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<LocalDateTime> cq = cb.createQuery(LocalDateTime.class);
        Root<QualityHold> root = cq.from(QualityHold.class);
        cq.select(cb.greatest(root.<LocalDateTime>get("createdAt")));
        return em.createQuery(cq).getResultList().stream().findFirst().orElse(null);
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<QualityHold> root, QualityHoldQuery query) {
        List<Predicate> list = new ArrayList<>();
        if (has(query.getStatus())) {
            list.add(cb.equal(root.get("status"), query.getStatus()));
        }
        if (has(query.getDefectType())) {
            list.add(cb.equal(root.get("defectType"), query.getDefectType()));
        }
        if (has(query.getSeverity())) {
            list.add(cb.equal(root.get("severity"), query.getSeverity()));
        }
        if (has(query.getBlockCode())) {
            list.add(cb.like(root.get("snapBlockCode"), like(query.getBlockCode())));
        }
        if (has(query.getSlabCode())) {
            list.add(cb.like(root.get("snapSlabCode"), like(query.getSlabCode())));
        }
        return list.toArray(new Predicate[0]);
    }

    private List<Order> orderBy(CriteriaBuilder cb, Root<QualityHold> root, QualityHoldQuery query) {
        String key = query.getOrderBy() == null ? "time" : query.getOrderBy();
        Path<?> path = switch (key) {
            case "code" -> root.get("holdNo");
            case "severity" -> root.get("severity");
            default -> root.get("createdAt");
        };
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
