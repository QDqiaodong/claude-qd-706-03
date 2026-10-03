package com.stone.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.stone.mill.entity.SawOutput;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * 模块十：逐片产出仓储。
 *
 * <p>唯一性以数据库约束为准：
 * uk_saw_output_token(batch_id, client_token) 兜断网重试 / 重复扫码；
 * uk_saw_output_active_slab(active_slab_id) 兜同一大板跨批次重复归属。
 */
@Repository
public class SawOutputRepository {

    @PersistenceContext
    private EntityManager em;

    public Optional<SawOutput> findById(Long id) {
        return Optional.ofNullable(em.find(SawOutput.class, id));
    }

    public void save(SawOutput output) {
        if (output.getId() == null) {
            em.persist(output);
        } else {
            em.merge(output);
        }
    }

    public void flush() {
        em.flush();
    }

    /** 幂等键取首次产出（同批次同 token），断网重试直接返回它。 */
    public SawOutput findByToken(Long batchId, String clientToken) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutput> cq = cb.createQuery(SawOutput.class);
        Root<SawOutput> root = cq.from(SawOutput.class);
        cq.select(root).where(cb.equal(root.get("batchId"), batchId),
                cb.equal(root.get("clientToken"), clientToken));
        return em.createQuery(cq).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }

    /** 该大板当前的有效产出（跨批次唯一归属），无则 null。 */
    public SawOutput findActiveBySlab(Long slabId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutput> cq = cb.createQuery(SawOutput.class);
        Root<SawOutput> root = cq.from(SawOutput.class);
        cq.select(root).where(cb.equal(root.get("slabId"), slabId),
                cb.equal(root.get("status"), "ACTIVE"));
        return em.createQuery(cq).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }

    /** 该大板的全部产出记录（含已作废 / 曾归属），大板反查历次更正用。 */
    public List<SawOutput> listBySlab(Long slabId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutput> cq = cb.createQuery(SawOutput.class);
        Root<SawOutput> root = cq.from(SawOutput.class);
        cq.select(root).where(cb.equal(root.get("slabId"), slabId))
                .orderBy(cb.asc(root.get("registeredAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    /** 一批大板的当前有效产出（大板列表/详情批量补「所属批次」用）。 */
    public List<SawOutput> findActiveBySlabIds(List<Long> slabIds) {
        if (slabIds == null || slabIds.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutput> cq = cb.createQuery(SawOutput.class);
        Root<SawOutput> root = cq.from(SawOutput.class);
        cq.select(root).where(root.get("slabId").in(slabIds),
                cb.equal(root.get("status"), "ACTIVE"));
        return em.createQuery(cq).getResultList();
    }

    /** 把某大板当来源余料的后续产出（作废返切余料前检查「被后续批次引用」）。 */
    public List<SawOutput> listBySourceSlab(Long sourceSlabId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutput> cq = cb.createQuery(SawOutput.class);
        Root<SawOutput> root = cq.from(SawOutput.class);
        cq.select(root).where(cb.equal(root.get("sourceSlabId"), sourceSlabId),
                cb.equal(root.get("status"), "ACTIVE"));
        return em.createQuery(cq).getResultList();
    }

    public List<SawOutput> listByBatch(Long batchId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutput> cq = cb.createQuery(SawOutput.class);
        Root<SawOutput> root = cq.from(SawOutput.class);
        cq.select(root).where(cb.equal(root.get("batchId"), batchId))
                .orderBy(cb.asc(root.get("registeredAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public List<SawOutput> listActiveByBatch(Long batchId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutput> cq = cb.createQuery(SawOutput.class);
        Root<SawOutput> root = cq.from(SawOutput.class);
        cq.select(root).where(cb.equal(root.get("batchId"), batchId),
                cb.equal(root.get("status"), "ACTIVE"))
                .orderBy(cb.asc(root.get("seqNo")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }
}
