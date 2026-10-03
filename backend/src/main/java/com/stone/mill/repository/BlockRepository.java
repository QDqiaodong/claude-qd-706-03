package com.stone.mill.repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.dto.Queries.BlockQuery;
import com.stone.mill.dto.Views.BlockSummary;
import com.stone.mill.entity.Block;

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
 * 模块一：荒料仓储。
 *
 * <p>数据访问全部手写 JPA Criteria API：注入 EntityManager，用 CriteriaBuilder 动态拼
 * WHERE / ORDER BY，分页由 countQuery + 结果查询两次查询完成，不借助 Spring Data 的派生查询。
 */
@Repository
public class BlockRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<Block> findById(Long id) {
        return Optional.ofNullable(em.find(Block.class, id));
    }

    public void save(Block block) {
        if (block.getId() == null) {
            em.persist(block);
        } else {
            em.merge(block);
        }
    }

    public Block findByCode(String blockCode) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Block> cq = cb.createQuery(Block.class);
        Root<Block> root = cq.from(Block.class);
        cq.select(root).where(cb.equal(root.get("blockCode"), blockCode));
        return em.createQuery(cq).getResultList().stream().findFirst().orElse(null);
    }

    /** 动态条件查询（石种 / 状态 / 矿口 / 体积区间 / 长宽下限 / 编号模糊）。 */
    public List<Block> search(BlockQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Block> cq = cb.createQuery(Block.class);
        Root<Block> root = cq.from(Block.class);
        cq.select(root);
        cq.where(predicates(cb, root, query));
        cq.orderBy(orderBy(cb, root, query));

        TypedQuery<Block> typed = em.createQuery(cq);
        typed.setFirstResult(query.offset());
        typed.setMaxResults(query.limit());
        return typed.getResultList();
    }

    /** 与 search 同一套条件的计数查询，用于分页。 */
    public long count(BlockQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Block> root = cq.from(Block.class);
        cq.select(cb.count(root));
        cq.where(predicates(cb, root, query));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 台账底部汇总：荒料数、体积合计、重量合计、采购金额、各状态数量。 */
    public BlockSummary summarize(BlockQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Block> root = cq.from(Block.class);
        Path<BigDecimal> volume = root.get("volumeM3");
        Path<BigDecimal> weight = root.get("weightTon");
        Path<BigDecimal> price = root.get("purchasePrice");
        cq.multiselect(cb.count(root), cb.sum(volume), cb.sum(weight), cb.sum(cb.prod(volume, price)));
        cq.where(predicates(cb, root, query));

        Object[] row = em.createQuery(cq).getSingleResult();
        BlockSummary summary = new BlockSummary();
        summary.setBlockCount(toLong(row[0]));
        summary.setTotalVolume(toDecimal(row[1]));
        summary.setTotalWeight(toDecimal(row[2]));
        summary.setPurchaseAmount(toDecimal(row[3]));
        summary.setPendingCount(countByStatus("PENDING"));
        summary.setProcessingCount(countByStatus("PROCESSING"));
        summary.setDoneCount(countByStatus("DONE"));
        summary.setArchivedCount(countByStatus("ARCHIVED"));
        return summary;
    }

    public long countByStatus(String status) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Block> root = cq.from(Block.class);
        cq.select(cb.count(root)).where(cb.equal(root.get("status"), status));
        Long total = em.createQuery(cq).getSingleResult();
        return total == null ? 0L : total;
    }

    /** 左侧树 / 下拉用的全量荒料，按编号升序。 */
    public List<Block> listAll() {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Block> cq = cb.createQuery(Block.class);
        Root<Block> root = cq.from(Block.class);
        cq.select(root).orderBy(cb.asc(root.get("blockCode")));
        return em.createQuery(cq).getResultList();
    }

    private Predicate[] predicates(CriteriaBuilder cb, Root<Block> root, BlockQuery query) {
        List<Predicate> list = new ArrayList<>();
        if (has(query.getStoneType())) {
            list.add(cb.equal(root.get("stoneType"), query.getStoneType()));
        }
        if (has(query.getStatus())) {
            list.add(cb.equal(root.get("status"), query.getStatus()));
        }
        if (has(query.getQuarry())) {
            list.add(cb.like(root.get("quarry"), like(query.getQuarry())));
        }
        if (has(query.getKeyword())) {
            String kw = like(query.getKeyword());
            Path<String> code = root.get("blockCode");
            Path<String> quarry = root.get("quarry");
            Path<String> supplier = root.get("supplier");
            list.add(cb.or(cb.like(code, kw), cb.like(quarry, kw), cb.like(supplier, kw)));
        }
        if (query.getMinVolume() != null) {
            Path<BigDecimal> volume = root.get("volumeM3");
            list.add(cb.ge(volume, query.getMinVolume()));
        }
        if (query.getMaxVolume() != null) {
            Path<BigDecimal> volume = root.get("volumeM3");
            list.add(cb.le(volume, query.getMaxVolume()));
        }
        if (query.getMinLength() != null) {
            Path<Integer> length = root.get("lengthCm");
            list.add(cb.ge(length, query.getMinLength()));
        }
        if (query.getMinWidth() != null) {
            Path<Integer> width = root.get("widthCm");
            list.add(cb.ge(width, query.getMinWidth()));
        }
        if (query.getInboundFrom() != null) {
            Path<java.time.LocalDate> date = root.get("inboundDate");
            list.add(cb.greaterThanOrEqualTo(date, query.getInboundFrom()));
        }
        if (query.getInboundTo() != null) {
            Path<java.time.LocalDate> date = root.get("inboundDate");
            list.add(cb.lessThanOrEqualTo(date, query.getInboundTo()));
        }
        return list.toArray(new Predicate[0]);
    }

    /** 动态排序：code / volume / weight / date。 */
    private List<Order> orderBy(CriteriaBuilder cb, Root<Block> root, BlockQuery query) {
        String key = query.getOrderBy() == null ? "code" : query.getOrderBy();
        Path<?> path = switch (key) {
            case "volume" -> root.get("volumeM3");
            case "weight" -> root.get("weightTon");
            case "date" -> root.get("inboundDate");
            default -> root.get("blockCode");
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
