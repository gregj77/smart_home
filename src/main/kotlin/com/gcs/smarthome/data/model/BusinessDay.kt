package com.gcs.smarthome.data.model

import org.hibernate.Hibernate
import java.time.LocalDate
import javax.persistence.*
import kotlin.jvm.Transient

@Entity
class BusinessDay (val reference: LocalDate) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Short = 0

    @Transient
    val year: Int = reference.year

    @Transient
    val yearAndMonth: Int = reference.year * 100 + reference.month.value

    @JoinColumn(name = "businessDayId")
    @OneToMany(fetch = FetchType.LAZY)
    val readings: List<DeviceReading> = emptyList()


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as BusinessDay
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    @Override
    override fun toString(): String {
        return this::class.simpleName + "(id = $id )"
    }

}