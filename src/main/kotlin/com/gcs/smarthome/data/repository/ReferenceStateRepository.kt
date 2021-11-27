package com.gcs.smarthome.data.repository

import com.gcs.smarthome.data.model.ReferenceState
import com.gcs.smarthome.data.model.ReferenceType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface ReferenceStateRepository : JpaRepository<ReferenceState, Int> {
    fun findAllByReferenceTypeOrderByBusinessDayDesc(type: ReferenceType): List<ReferenceState>
    fun findFirstByReferenceTypeOrderByBusinessDayDesc(type: ReferenceType): Optional<ReferenceState>
}