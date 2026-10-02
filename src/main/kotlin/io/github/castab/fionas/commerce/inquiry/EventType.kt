package io.github.castab.fionas.commerce.inquiry

/** Fiona's supported inquiry occasions, in public selection order. */
enum class EventType(
    val label: String,
) {
    BIRTHDAY("Birthday"),
    WEDDING("Wedding"),
    CORPORATE("Corporate"),
    SCHOOL_EVENT("School event"),
    NEIGHBORHOOD_EVENT("Neighborhood event"),
    OTHER("Other"),
}
