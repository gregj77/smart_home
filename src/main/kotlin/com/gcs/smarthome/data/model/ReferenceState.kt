package com.gcs.smarthome.data.model

import org.hibernate.Hibernate
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.persistence.*

@Entity
class ReferenceState(
   val value: BigDecimal,

   @Enumerated(EnumType.ORDINAL)
   val referenceType: ReferenceType,

   val createdOn: LocalDateTime,

   @JoinColumn(name = "import_reading_id")
   @ManyToOne(fetch = FetchType.LAZY)
   val importReading: DeviceReading?,

   @JoinColumn(name = "export_reading_id")
   @ManyToOne(fetch = FetchType.LAZY)
   val exportReading: DeviceReading?,

   @JoinColumn(name = "businessDayId")
   @ManyToOne(fetch = FetchType.LAZY)
   val businessDay: BusinessDay
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Short = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as ReferenceState
        return id == other.id
    }

    override fun hashCode(): Int = javaClass.hashCode()

    @Override
    override fun toString(): String {
        return this::class.simpleName + "(id = $id )"
    }

}