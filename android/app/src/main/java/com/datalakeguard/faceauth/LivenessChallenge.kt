package com.datalakeguard.faceauth

class LivenessChallenge private constructor(
    val challenge1: ChallengeType,
    val challenge2: ChallengeType
) {
    
    enum class ChallengeType(val displayName: String, val instruction: String) {
        BLINK("Blink", "blink your eyes"),
        SMILE("Smile", "smile"),
        TURN_LEFT("Turn Left", "turn your head slightly to the left"),
        TURN_RIGHT("Turn Right", "turn your head slightly to the right")
    }
    
    private var challenge1Passed = false
    private var challenge2Passed = false
    
    // Frame history for blink detection
    private val earHistory = mutableListOf<Float>()
    private val maxHistorySize = 10
    
    // Smile/turn detection
    private var consecutiveDetections = 0
    private var requiredConsecutiveFrames = 3
    
    val currentChallenge: ChallengeType
        get() = if (!challenge1Passed) challenge1 else challenge2
    
    companion object {
        fun createRandom(): LivenessChallenge {
            val challenges = ChallengeType.values().toList().shuffled()
            return LivenessChallenge(challenges[0], challenges[1])
        }
    }
    
    // Add EAR to history
    fun addEar(ear: Float) {
        earHistory.add(ear)
        if (earHistory.size > maxHistorySize) {
            earHistory.removeAt(0)
        }
    }
    
    // Check if blink pattern detected in history
    private fun detectBlink(): Boolean {
        if (earHistory.size < 4) return false
        
        // Look for pattern: open → closed → open
        for (i in 2 until earHistory.size - 1) {
            val before = earHistory[i - 2]
            val current = earHistory[i]
            val after = earHistory[i + 1]
            
            // Eyes were open, then closed (EAR < 0.2), then open again
            if (before > 0.25 && current < 0.2 && after > 0.25) {
                return true
            }
        }
        return false
    }
    
    fun checkChallenge(landmarks: FaceMeshLiveness.Landmarks): LivenessResult {
        val challenge = currentChallenge
        val faceMeshLiveness = FaceMeshLiveness()
        
        when (challenge) {
            ChallengeType.BLINK -> {
                val ear = faceMeshLiveness.computeEAR(landmarks)
                addEar(ear)
                
                if (detectBlink()) {
                    earHistory.clear()
                    markChallengePassed()
                    return LivenessResult.PASSED
                }
            }
            ChallengeType.SMILE -> {
                if (faceMeshLiveness.checkSmile(landmarks)) {
                    consecutiveDetections++
                    if (consecutiveDetections >= requiredConsecutiveFrames) {
                        markChallengePassed()
                        return LivenessResult.PASSED
                    }
                } else {
                    consecutiveDetections = 0
                }
            }
            ChallengeType.TURN_LEFT -> {
                if (faceMeshLiveness.checkHeadTurn(landmarks, "left")) {
                    consecutiveDetections++
                    if (consecutiveDetections >= requiredConsecutiveFrames) {
                        markChallengePassed()
                        return LivenessResult.PASSED
                    }
                } else {
                    consecutiveDetections = 0
                }
            }
            ChallengeType.TURN_RIGHT -> {
                if (faceMeshLiveness.checkHeadTurn(landmarks, "right")) {
                    consecutiveDetections++
                    if (consecutiveDetections >= requiredConsecutiveFrames) {
                        markChallengePassed()
                        return LivenessResult.PASSED
                    }
                } else {
                    consecutiveDetections = 0
                }
            }
        }
        
        return LivenessResult.PENDING
    }
    
    private fun markChallengePassed() {
        consecutiveDetections = 0
        if (!challenge1Passed) {
            challenge1Passed = true
        } else if (!challenge2Passed) {
            challenge2Passed = true
        }
    }
    
    fun isComplete(): Boolean {
        return challenge1Passed && challenge2Passed
    }
    
    fun getProgress(): String {
        return when {
            !challenge1Passed -> "Challenge 1/2: ${challenge1.displayName}"
            !challenge2Passed -> "Challenge 2/2: ${challenge2.displayName}"
            else -> "Complete"
        }
    }
}

enum class LivenessResult {
    PENDING,
    PASSED,
    FAILED
}