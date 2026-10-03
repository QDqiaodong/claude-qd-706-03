package com.stone.mill.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.dto.Queries.DeliveryQuery;
import com.stone.mill.dto.Views.DeliverySummary;
import com.stone.mill.entity.Delivery;
import com.stone.mill.entity.DeliveryItem;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/** 模块五：出库发货仓储（发货单与明细都走手写 Criteria）。 */
@Repository
public class DeliveryRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<Delivery> findById(Long id) {
        return Optional.ofNullable(em.find(Delivery.class, id));
    }

    /**
     * 行级悲观写锁取发货单：加板明细与状态流转共用同一把行锁，
     * 避免「发运的同时还在加大板」造成状态与库存不一致。
     */
    public Optional<Delivery> findByIdForUpdate(Long id) {
        return em.createQuery("SELECT d FROM Delivery d WHERE d.id = :id", Delivery.class)
                .setParameter("id", id)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst();
    }

    public void save(Delivery delivery) {
        if (delivery.getId() == null) {
            em.persist(delivery);
        } else {
            em.merge(delivery);
        }
    }

    public Delivery findByNo(String deliveryNo) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Delivery> cq = cb.createQuery(Delivery.class);
        Root<Delivery> root = cq.from(Delivery.class);
        cq.select(root).where(cb.equal(root.get("deliveryNo"), deliveryNo));
        return em.createQuery(cq).getResultList().stream().findFirst().orElse(null);
    }

    public List<Delivery> search(DeliveryQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Delivery> cq = cb.createQuery(Delivery.class);
        Root<Delivery> root = cq.from(Delivery.class);
        cq.select(root);
        cq.where(predicates(cb, root, query));
        cq.orderBy(orderBy(cb, root, query));

        TypedQuery<Delivery> typed = em.createQuery(cq);
        typed.setFirstResult(query.offset());
        typed.setMaxResults(query.limit());
        return typed.getResultList();
    }

    public long count(DeliveryQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Delivery> root = cq.from(Delivery.class);
        cq.select(cb.count(root));
        cq.where(predicates(cb, root, query));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 发货页底部金额 / 面积 / 各状态汇总。 */
    public DeliverySummary summarize(DeliveryQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Delivery> root = cq.from(Delivery.class);
        Path<BigDecimal> amount = root.get("totalAmount");
        cq.multiselect(cb.count(root), cb.sum(amount));
        cq.where(predicates(cb, root, query));

        Object[] row = em.createQuery(cq).getSingleResult();
        DeliverySummary summary = new DeliverySummary();
        summary.setDeliveryCount(toLong(row[0]));
        summary.setTotalAmount(toDecimal(row[1]));
        summary.setTotalArea(sumItemArea(null));
        summary.setLoadingCount(countByStatus("LOADING"));
        summary.setShippedCount(countByStatus("SHIPPED"));
        summary.setSignedCount(countByStatus("SIGNED"));
        summary.setPaidCount(countByStatus("PAID"));
        return summary;
    }

    public long countByStatus(String status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Delivery> root = cq.from(Delivery.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("status"), status));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    // ---------------- 明细 ----------------

    public Optional<DeliveryItem> findItemById(Long id) {
        return Optional.ofNullable(em.find(DeliveryItem.class, id));
    }

    public void saveItem(DeliveryItem item) {
        if (item.getId() == null) {
            em.persist(item);
        } else {
            em.merge(item);
        }
    }

    /** 发货单下的大板明细。 */
    public List<DeliveryItem> listItems(Long deliveryId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<DeliveryItem> cq = cb.createQuery(DeliveryItem.class);
        Root<DeliveryItem> root = cq.from(DeliveryItem.class);
        cq.select(root).where(cb.equal(root.get("deliveryId"), deliveryId)).orderBy(cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    /** 某块大板出现在哪些发货明细里（不限发货单状态）。 */
    public List<DeliveryItem> findItemsBySlab(Long slabId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<DeliveryItem> cq = cb.createQuery(DeliveryItem.class);
        Root<DeliveryItem> root = cq.from(DeliveryItem.class);
        cq.select(root).where(cb.equal(root.get("slabId"), slabId)).orderBy(cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    /** 一批大板命中的发货明细，给发货列表批量算「质量挂起」用。 */
    public List<DeliveryItem> findItemsBySlabIds(List<Long> slabIds) {
        if (slabIds == null || slabIds.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<DeliveryItem> cq = cb.createQuery(DeliveryItem.class);
        Root<DeliveryItem> root = cq.from(DeliveryItem.class);
        cq.select(root).where(root.get("slabId").in(slabIds)).orderBy(cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    /** 删除明细行（报废收口时从待装车发货单移除），返回删掉的行数。 */
    public int deleteItem(Long itemId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaDelete<DeliveryItem> cd = cb.createCriteriaDelete(DeliveryItem.class);
        Root<DeliveryItem> root = cd.from(DeliveryItem.class);
        cd.where(cb.equal(root.get("id"), itemId));
        return em.createQuery(cd).executeUpdate();
    }

    /** 明细面积合计（按发货单统计，传 null 统计全部）。 */
    public BigDecimal sumItemArea(Long deliveryId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> cq = cb.createQuery(BigDecimal.class);
        Root<DeliveryItem> root = cq.from(DeliveryItem.class);
        Path<BigDecimal> area = root.get("areaM2");
        cq.select(cb.sum(area));
        if (deliveryId != null) {
            cq.where(cb.equal(root.get("deliveryId"), deliveryId));
        }
        BigDecimal sum = em.createQuery(cq).getSingleResult();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // ---------------- 条件拼装 ----------------

    private Predicate[] predicates(CriteriaBuilder cb, Root<Delivery> root, DeliveryQuery query) {
        List<Predicate> list = new ArrayList<>();
        if (has(query.getStatus())) {
            list.add(cb.equal(root.get("status"), query.getStatus()));
        }
        if (has(query.getKeyword())) {
            String kw = like(query.getKeyword());
            Path<String> no = root.get("deliveryNo");
            Path<String> customer = root.get("customer");
            Path<String> plate = root.get("plateNo");
            Path<String> driver = root.get("driver");
            list.add(cb.or(cb.like(no, kw), cb.like(customer, kw), cb.like(plate, kw), cb.like(driver, kw)));
        }
        if (query.getDateFrom() != null) {
            Path<LocalDate> date = root.get("deliveryDate");
            list.add(cb.greaterThanOrEqualTo(date, query.getDateFrom()));
        }
        if (query.getDateTo() != null) {
            Path<LocalDate> date = root.get("deliveryDate");
            list.add(cb.lessThanOrEqualTo(date, query.getDateTo()));
        }
        if (query.getMinAmount() != null) {
            Path<BigDecimal> amount = root.get("totalAmount");
            list.add(cb.ge(amount, query.getMinAmount()));
        }
        return list.toArray(new Predicate[0]);
    }

    /** 动态排序：amount / date / code。 */
    private List<Order> orderBy(CriteriaBuilder cb, Root<Delivery> root, DeliveryQuery query) {
        String key = query.getOrderBy() == null ? "date" : query.getOrderBy();
        Path<?> path = switch (key) {
            case "amount" -> root.get("totalAmount");
            case "code" -> root.get("deliveryNo");
            default -> root.get("deliveryDate");
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

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal toDecimal(Object value) {
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }
}
