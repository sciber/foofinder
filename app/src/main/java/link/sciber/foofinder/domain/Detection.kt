package link.sciber.foofinder.domain

data class Detection(
        val boundingBoxes: List<BoundingBox>,
        val area: DetectionArea,
        // Performance metrics
        val inferenceMs: Long = -1,
        val fps: Float = -1f,
        // Counts: raw before NMS, and after NMS (usually equals boundingBoxes.size)
        val rawDetections: Int = 0,
        val afterNmsDetections: Int = boundingBoxes.size
)
