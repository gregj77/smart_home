package com.gcs.smarthome.logic.message

import java.time.LocalDate

data class BusinessDayOpenEvent(val businessDayId: Short, val date: LocalDate)