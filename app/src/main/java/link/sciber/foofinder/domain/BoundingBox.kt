package link.sciber.foofinder.domain

data class BoundingBox(
    val startX: Float,
    val startY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val classId: Int = 0,
    val className: String = "Foo"
)
