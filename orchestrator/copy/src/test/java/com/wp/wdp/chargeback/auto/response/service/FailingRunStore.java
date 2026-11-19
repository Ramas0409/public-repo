package com.wp.wdp.chargeback.auto.response.service;

import com.wp.wdp.chargeback.auto.response.entity.ClassifierRunEntity;

/** A run store whose every write fails, as a database that cannot be reached looks from here. */
public class FailingRunStore extends StubRunStore {

    @Override
    public Long recordRun(ClassifierRunEntity run) {
        throw new IllegalStateException("the database could not be reached for POCVISA13S01");
    }
}
