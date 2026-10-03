package com.stone.mill.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.dto.Queries.OrderQuery;
import com.stone.mill.entity.CuttingOrder;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/** 模块三：加工工单仓储（Criteria 动态条件 + 动态排序 + 分页）。 */
@Repository
public class CuttingOrderRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<CuttingOrder> findById(Long id) {
        return Optional.ofNullable(em.find(CuttingOrder.class, id));
    }

    /**
     * 悲观行锁取工单（必须在事务内调用）：锯切批次收口回写完成数时串行化，
     * 两个批次同时收口 / 重复点击收口不会在完成数上丢更新。
     */
    public CuttingOrder lockById(Long id) {
        return em.createQuery("SELECT o FROM CuttingOrder o WHERE o.id = :id", CuttingOrder.class)
                .setParameter("id", id)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst().orElse(null);
    }

    public void save(CuttingOrder order) {
        if (order.getId() == null) {
            em.persist(order);
        } else {
            em.merge(order);
        }
    }

    public CuttingOrder findByOrderNo(String orderNo) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<CuttingOrder> cq = cb.createQuery(CuttingOrder.class);
        Root<CuttingOrder> root = cq.from(CuttingOrder.class);
        cq.select(root).where(cb.equal(root.get("orderNo"), orderNo));
        return em.createQuery(cq).getResultList().stream().findFirst().orElse(null);
    }

    public List<CuttingOrder> search(OrderQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<CuttingOrder> cq = cb.createQuery(CuttingOrder.class);
        Root<CuttingOrder> root = cq.from(CuttingOrder.class);
        cq.select(root);
        cq.where(predicates(cb, root, query));
        cq.orderBy(orderBy(cb, root, query));

        TypedQuery<CuttingOrder> typed = em.createQuery(cq);
        typed.setFirstResult(query.offset());
        typed.setMaxResults(query.limit());
        return typed.getResultList();
    }

    public long count(OrderQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<CuttingOrder> root = cq.from(CuttingOrder.class);
        cq.select(cb.count(root));
        cq.where(predicates(cb, root, query));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    public long countByStatus(String status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<CuttingOrder> root = cq.from(CuttingOrder.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("status"), status));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 已完工板数合计（首页进度口径）。 */
    public long sumDoneCount() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<CuttingOrder> root = cq.from(CuttingOrder.class);
        Path<Integer> done = root.get("doneCount");
        cq.select(cb.sum(done).as(Long.class));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<CuttingOrder> root, OrderQuery query) {
        List<Predicate> list = new ArrayList<>();
        if (query.getBlockId() != null) {
            list.add(cb.equal(root.get("blockId"), query.getBlockId()));
        }
        if (has(query.getSawType())) {
            list.add(cb.equal(root.get("sawType"), query.getSawType()));
        }
        if (has(query.getStatus())) {
            list.add(cb.equal(root.get("status"), query.getStatus()));
        }
        if (has(query.getTeam())) {
            list.add(cb.equal(root.get("team"), query.getTeam()));
        }
        if (has(query.getKeyword())) {
            Path<String> no = root.get("orderNo");
            Path<String> remark = root.get("remark");
            list.add(cb.or(cb.like(no, like(query.getKeyword())), cb.like(remark, like(query.getKeyword()))));
        }
        return list.toArray(new Predicate[0]);
    }

    /** 动态排序：date / plan / progress（按完成率）。 */
    private List<Order> orderBy(CriteriaBuilder cb, Root<CuttingOrder> root, OrderQuery query) {
        String key = query.getOrderBy() == null ? "date" : query.getOrderBy();
        if ("progress".equals(key)) {
            // 完成率 = 已完成板数 / 计划板数，用商表达式排序
            Path<Integer> done = root.get("doneCount");
            Path<Integer> plan = root.get("planCount");
            jakarta.persistence.criteria.Expression<Number> rate = cb.quot(done, plan);
            return List.of(query.isDesc() ? cb.desc(rate) : cb.asc(rate));
        }
        Path<?> path = switch (key) {
            case "plan" -> root.get("planCount");
            case "code" -> root.get("orderNo");
            default -> root.get("startDate");
        };
        Order order = query.isDesc() ? cb.desc(path) : cb.asc(path);
        return List.of(order);
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        return "%" + value.trim() + "%";
    }
}
