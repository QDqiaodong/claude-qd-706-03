package com.stone.mill.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.stone.mill.entity.SawOutputAudit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/** 模块十二：谱系留痕仓储（只追加，按时间 / id 升序读出）。 */
@Repository
public class SawAuditRepository {

    @PersistenceContext
    private EntityManager em;

    public void save(SawOutputAudit audit) {
        em.persist(audit);
    }

    public List<SawOutputAudit> listByBatch(Long batchId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutputAudit> cq = cb.createQuery(SawOutputAudit.class);
        Root<SawOutputAudit> root = cq.from(SawOutputAudit.class);
        cq.select(root).where(cb.equal(root.get("batchId"), batchId))
                .orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    /** 一批批次的合并时间线（归属更正会同时影响两个批次，大板详情要能看到）。 */
    public List<SawOutputAudit> listByBatches(List<Long> batchIds) {
        if (batchIds == null || batchIds.isEmpty()) {
            return List.of();
        }
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutputAudit> cq = cb.createQuery(SawOutputAudit.class);
        Root<SawOutputAudit> root = cq.from(SawOutputAudit.class);
        cq.select(root).where(root.get("batchId").in(batchIds))
                .orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }

    public List<SawOutputAudit> listByOutput(Long outputId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<SawOutputAudit> cq = cb.createQuery(SawOutputAudit.class);
        Root<SawOutputAudit> root = cq.from(SawOutputAudit.class);
        cq.select(root).where(cb.equal(root.get("outputId"), outputId))
                .orderBy(cb.asc(root.get("createdAt")), cb.asc(root.get("id")));
        return em.createQuery(cq).getResultList();
    }
}
