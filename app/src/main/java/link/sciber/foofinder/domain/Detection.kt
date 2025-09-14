package link.sciber.foofinder.domain

data class Detection (
    val boundingBoxes: List<BoundingBox>,
    val area: DetectionArea
)