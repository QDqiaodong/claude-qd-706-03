package com.stone.mill.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.entity.SawBatch;
import com.stone.mill.entity.SawOutput;
import com.stone.mill.entity.Slab;
import com.stone.mill.repository.SawBatchRepository;
import com.stone.mill.repository.SawOutputRepository;
import com.stone.mill.repository.SlabRepository;

/**
 * 独立事务桥：逐片登记撞 uk_saw_output_active_slab / 批次号唯一键后，
 * 当前事务已被标记 rollback-only，必须在【全新事务】里读出先到的归属记录回带给后到人员。
 */
@Component
public class SawTxBridge {

    private final SawOutputRepository outputRepository;
    private final SawBatchRepository batchRepository;
    private final SlabRepository slabRepository;

    public SawTxBridge(SawOutputRepository outputRepository, SawBatchRepository batchRepository,
                       SlabRepository slabRepository) {
        this.outputRepository = outputRepository;
        this.batchRepository = batchRepository;
        this.slabRepository = slabRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SawOutput findActiveOutputBySlab(Long slabId) {
        return outputRepository.findActiveBySlab(slabId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SawOutput findOutputById(Long id) {
        return outputRepository.findById(id).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SawOutput findOutputByToken(Long batchId, String clientToken) {
        return outputRepository.findByToken(batchId, clientToken);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SawBatch findBatchById(Long id) {
        return batchRepository.findById(id).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Slab findSlabById(Long id) {
        return id == null ? null : slabRepository.findById(id).orElse(null);
    }
}
