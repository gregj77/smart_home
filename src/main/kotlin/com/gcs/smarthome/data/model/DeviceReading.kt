package com.gcs.smarthome.data.model

import org.hibernate.Hibernate
import java.math.BigDecimal
import java.time.LocalTime
import jakarta.persistence.*


@Entity
class DeviceReading(
    @Enumerated(EnumType.ORDINAL)
    val deviceType: DeviceType,

    val value: BigDecimal,

    val createdOnTime: LocalTime,

    val businessDayId: Short
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Int = 0


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as DeviceReading
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    @Override
    override fun toString(): String {
        return this::class.simpleName + "(id = $id )"
    }
}