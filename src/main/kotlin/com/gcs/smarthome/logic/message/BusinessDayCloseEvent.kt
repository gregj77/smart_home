package com.gcs.smarthome.logic.message

import java.time.LocalDate

data class BusinessDayCloseEvent(val businessDayId: Short, val date: LocalDate)