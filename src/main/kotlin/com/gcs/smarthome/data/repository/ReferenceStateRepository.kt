package com.gcs.smarthome.data.repository

import com.gcs.smarthome.data.model.ReferenceState
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReferenceStateRepository : JpaRepository<ReferenceState, Int> {
}