package com.stone.mill.repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.dto.Queries.SlabQuery;
import com.stone.mill.dto.Queries.ShippableQuery;
import com.stone.mill.dto.Views.SlabSummary;
import com.stone.mill.entity.Block;
import com.stone.mill.entity.QualityHold;
import com.stone.mill.entity.Slab;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * 模块二：大板仓储。
 *
 * <p>同样是手写 Criteria API：石种这类跨表条件走 {@code block_id IN (子查询)}，
 * 等级 / 状态 / 面积区间 / 长宽下限 / 编号模糊按参数动态拼接，排序与分页也动态。
 */
@Repository
public class SlabRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<Slab> findById(Long id) {
        return Optional.ofNullable(em.find(Slab.class, id));
    }

    public void save(Slab slab) {
        if (slab.getId() == null) {
            em.persist(slab);
        } else {
            em.merge(slab);
        }
    }

    /** 立即落库，让大板编号唯一冲突在业务代码处抛出（锯切登记新建大板用）。 */
    public void flush() {
        em.flush();
    }

    public Slab findByCode(String slabCode) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Slab> cq = cb.createQuery(Slab.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(root).where(cb.equal(root.get("slabCode"), slabCode));
        return em.createQuery(cq).getResultList().stream().findFirst().orElse(null);
    }

    /** 大板库存筛选：石种 / 等级 / 状态 / 表面处理 / 面积区间 / 长宽下限 / 编号模糊。 */
    public List<Slab> search(SlabQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Slab> cq = cb.createQuery(Slab.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(root);
        cq.where(predicates(cb, cq, root, query));
        cq.orderBy(orderBy(cb, root, query));

        TypedQuery<Slab> typed = em.createQuery(cq);
        typed.setFirstResult(query.offset());
        typed.setMaxResults(query.limit());
        return typed.getResultList();
    }

    public long count(SlabQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(cb.count(root));
        cq.where(predicates(cb, cq, root, query));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /**
     * 可发货大板筛选：只取在库 / 已预定（由调用方给状态集合），
     * 再叠加等级、面积区间、长宽下限与编号模糊，按面积或长度排序分页。
     * 质量冻结：存在未收口（隔离中 / 待复检）隔离记录的大板一律不出现。
     */
    public List<Slab> searchShippable(ShippableQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Slab> cq = cb.createQuery(Slab.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(root);
        cq.where(shippablePredicates(cb, cq, root, query));
        cq.orderBy(shippableOrder(cb, root, query));

        TypedQuery<Slab> typed = em.createQuery(cq);
        typed.setFirstResult(query.offset());
        typed.setMaxResults(query.limit());
        return typed.getResultList();
    }

    public long countShippable(ShippableQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(cb.count(root));
        cq.where(shippablePredicates(cb, cq, root, query));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 库存页顶部汇总：总数、总面积、在库面积、各状态与各等级数量。 */
    public SlabSummary summarize(SlabQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Slab> root = cq.from(Slab.class);
        Path<BigDecimal> area = root.get("areaM2");
        cq.multiselect(cb.count(root), cb.sum(area));
        cq.where(predicates(cb, cq, root, query));

        Object[] row = em.createQuery(cq).getSingleResult();
        SlabSummary summary = new SlabSummary();
        summary.setSlabCount(toLong(row[0]));
        summary.setTotalArea(toDecimal(row[1]));
        summary.setInstockArea(sumAreaByStatus("INSTOCK"));
        summary.setInstockCount(countByStatus("INSTOCK"));
        summary.setReservedCount(countByStatus("RESERVED"));
        summary.setOutboundCount(countByStatus("OUTBOUND"));
        summary.setScrapCount(countByStatus("SCRAP"));
        summary.setGradeA(countByGrade("A"));
        summary.setGradeB(countByGrade("B"));
        summary.setGradeC(countByGrade("C"));
        summary.setGradeDefect(countByGrade("DEFECT"));
        return summary;
    }

    public long countByBlock(Long blockId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("blockId"), blockId));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 某荒料下全部大板，排板重算与左侧树都用它。 */
    public List<Slab> listByBlock(Long blockId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Slab> cq = cb.createQuery(Slab.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(root).where(cb.equal(root.get("blockId"), blockId)).orderBy(cb.asc(root.get("slabCode")));
        return em.createQuery(cq).getResultList();
    }

    /** 下拉 / 树 / 明细补字段用的全量大板，按编号升序。 */
    public List<Slab> listAll() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Slab> cq = cb.createQuery(Slab.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(root).orderBy(cb.asc(root.get("slabCode")));
        return em.createQuery(cq).getResultList();
    }

    public long countByStatus(String status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("status"), status));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 某荒料已出大板的面积合计。 */
    public BigDecimal sumAreaByBlock(Long blockId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> cq = cb.createQuery(BigDecimal.class);
        Root<Slab> root = cq.from(Slab.class);
        Path<BigDecimal> area = root.get("areaM2");
        cq.select(cb.sum(area)).where(cb.equal(root.get("blockId"), blockId));
        BigDecimal sum = em.createQuery(cq).getSingleResult();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public long countByGrade(String grade) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Slab> root = cq.from(Slab.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("grade"), grade));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    public BigDecimal sumAreaByStatus(String status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> cq = cb.createQuery(BigDecimal.class);
        Root<Slab> root = cq.from(Slab.class);
        Path<BigDecimal> area = root.get("areaM2");
        cq.select(cb.sum(area)).where(cb.equal(root.get("status"), status));
        BigDecimal sum = em.createQuery(cq).getSingleResult();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private Predicate[] predicates(CriteriaBuilder cb, CriteriaQuery<?> parent, Root<Slab> root, SlabQuery query) {
        List<Predicate> list = new ArrayList<>();
        if (has(query.getStoneType())) {
            // 跨表条件：block_id IN (SELECT id FROM mill_block WHERE stone_type = ?)
            Subquery<Long> sub = parent.subquery(Long.class);
            Root<Block> blockRoot = sub.from(Block.class);
            sub.select(blockRoot.get("id")).where(cb.equal(blockRoot.get("stoneType"), query.getStoneType()));
            list.add(cb.in(root.get("blockId")).value(sub));
        }
        if (has(query.getGrade())) {
            list.add(cb.equal(root.get("grade"), query.getGrade()));
        }
        if (has(query.getStatus())) {
            list.add(cb.equal(root.get("status"), query.getStatus()));
        }
        if (has(query.getFinish())) {
            list.add(cb.equal(root.get("finish"), query.getFinish()));
        }
        if (query.getBlockId() != null) {
            list.add(cb.equal(root.get("blockId"), query.getBlockId()));
        }
        if (has(query.getKeyword())) {
            String kw = like(query.getKeyword());
            Path<String> code = root.get("slabCode");
            Path<String> location = root.get("location");
            list.add(cb.or(cb.like(code, kw), cb.like(location, kw)));
        }
        if (query.getMinArea() != null) {
            Path<BigDecimal> area = root.get("areaM2");
            list.add(cb.ge(area, query.getMinArea()));
        }
        if (query.getMaxArea() != null) {
            Path<BigDecimal> area = root.get("areaM2");
            list.add(cb.le(area, query.getMaxArea()));
        }
        if (query.getMinLength() != null) {
            Path<Integer> length = root.get("lengthCm");
            list.add(cb.ge(length, query.getMinLength()));
        }
        if (query.getMinWidth() != null) {
            Path<Integer> width = root.get("widthCm");
            list.add(cb.ge(width, query.getMinWidth()));
        }
        return list.toArray(new Predicate[0]);
    }

    private Predicate[] shippablePredicates(CriteriaBuilder cb, CriteriaQuery<?> parent,
                                            Root<Slab> root, ShippableQuery query) {
        List<Predicate> list = new ArrayList<>();
        List<String> statuses = query.getStatuses();
        if (statuses != null && !statuses.isEmpty()) {
            Path<String> status = root.get("status");
            list.add(status.in(statuses));
        }
        List<String> grades = query.getGrades();
        if (grades != null && !grades.isEmpty()) {
            Path<String> grade = root.get("grade");
            list.add(grade.in(grades));
        }
        // 质量冻结：NOT EXISTS(该大板的未收口隔离记录)
        Subquery<Long> holdSub = parent.subquery(Long.class);
        Root<QualityHold> holdRoot = holdSub.from(QualityHold.class);
        holdSub.select(holdRoot.get("id"))
                .where(cb.equal(holdRoot.get("slabId"), root.get("id")),
                        holdRoot.get("status").in(com.stone.mill.dto.StoneDict.HOLD_ACTIVE_STATUS));
        list.add(cb.not(cb.exists(holdSub)));
        if (has(query.getKeyword())) {
            String kw = like(query.getKeyword());
            Path<String> code = root.get("slabCode");
            Path<String> location = root.get("location");
            list.add(cb.or(cb.like(code, kw), cb.like(location, kw)));
        }
        if (query.getMinArea() != null) {
            Path<BigDecimal> area = root.get("areaM2");
            list.add(cb.ge(area, query.getMinArea()));
        }
        if (query.getMaxArea() != null) {
            Path<BigDecimal> area = root.get("areaM2");
            list.add(cb.le(area, query.getMaxArea()));
        }
        if (query.getMinLength() != null) {
            Path<Integer> length = root.get("lengthCm");
            list.add(cb.ge(length, query.getMinLength()));
        }
        if (query.getMinWidth() != null) {
            Path<Integer> width = root.get("widthCm");
            list.add(cb.ge(width, query.getMinWidth()));
        }
        return list.toArray(new Predicate[0]);
    }

    /** 动态排序：area / length / code。 */
    private List<Order> orderBy(CriteriaBuilder cb, Root<Slab> root, SlabQuery query) {
        String key = query.getOrderBy() == null ? "area" : query.getOrderBy();
        Path<?> path = switch (key) {
            case "length" -> root.get("lengthCm");
            case "code" -> root.get("slabCode");
            case "grade" -> root.get("grade");
            default -> root.get("areaM2");
        };
        Order order = query.isDesc() ? cb.desc(path) : cb.asc(path);
        return List.of(order);
    }

    private List<Order> shippableOrder(CriteriaBuilder cb, Root<Slab> root, ShippableQuery query) {
        String key = query.getOrderBy() == null ? "area" : query.getOrderBy();
        Path<?> path = switch (key) {
            case "length" -> root.get("lengthCm");
            case "code" -> root.get("slabCode");
            default -> root.get("areaM2");
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
