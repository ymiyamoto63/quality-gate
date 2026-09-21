package com.qualitygate.job;

import com.qualitygate.domain.entity.Job;
import com.qualitygate.domain.model.JobType;

/** ジョブ種別ごとの処理。 */
public interface JobHandler {

    boolean supports(JobType type);

    void handle(Job job);
}
