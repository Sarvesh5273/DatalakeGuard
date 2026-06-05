import Foundation
import React

@objc(FaceAuthModule)
class FaceAuthModule: NSObject, RCTBridgeModule {
  
  static func moduleName() -> String! {
    return "FaceAuthModule"
  }
  
  static func requiresMainQueueSetup() -> Bool {
    return true
  }
  
  @objc func constantsToExport() -> [AnyHashable : Any]! {
    return [:]
  }
  
  @objc func startLivenessCamera(_ resolve: @escaping RCTPromiseResolveBlock,
                                   rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["success": true, "message": "Camera started"])
  }
  
  @objc func stopLivenessCamera(_ resolve: RCTPromiseResolveBlock,
                                  rejecter reject: RCTPromiseRejectBlock) {
    resolve(["success": true, "message": "Camera stopped"])
  }
  
  @objc func loadModel(_ modelName: String,
                       resolver resolve: @escaping RCTPromiseResolveBlock,
                       rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["success": true, "message": "Model loaded"])
  }
  
  @objc func detectFace(_ resolve: @escaping RCTPromiseResolveBlock,
                        rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["status": "landmarks_detected", "yaw": 0.05, "faceScore": 0.92])
  }
  
  @objc func enrollFace(_ workerId: String,
                        name: String,
                        resolver resolve: @escaping RCTPromiseResolveBlock,
                        rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["status": "enrolled", "workerId": workerId])
  }
  
  @objc func startAuthentication(_ resolve: @escaping RCTPromiseResolveBlock,
                                  rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["status": "ready", "challenge": "TURN_LEFT"])
  }
  
  @objc func processLivenessFrameForMatching(_ resolve: @escaping RCTPromiseResolveBlock,
                                               rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve([
      "status": "complete",
      "matched": true,
      "workerId": "demo_worker",
      "confidence": 0.97,
      "livenessPass": true,
      "authTimeMs": 450
    ])
  }
  
  @objc func processEnrollmentFrame(_ imagePath: String,
                                     resolver resolve: @escaping RCTPromiseResolveBlock,
                                     rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["status": "captured", "embeddingSize": 128])
  }
  
  @objc func finalizeEnrollment(_ workerId: String,
                                 name: String,
                                 resolver resolve: @escaping RCTPromiseResolveBlock,
                                 rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["status": "enrolled", "workerId": workerId])
  }
  
  @objc func getPendingCount(_ resolve: RCTPromiseResolveBlock,
                              rejecter reject: RCTPromiseRejectBlock) {
      resolve(0)
  }
  
  @objc func logAuthEvent(_ workerId: String,
                          confidence: NSNumber,
                          livenessPass: Bool,
                          resolver resolve: @escaping RCTPromiseResolveBlock,
                          rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve("logged")
  }
  
  @objc func syncToAWS(_ resolve: @escaping RCTPromiseResolveBlock,
                        rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve("Synced 0 records (iOS stub)")
  }
  
  @objc func getAllLogs(_ resolve: @escaping RCTPromiseResolveBlock,
                         rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve([])
  }
  
  @objc func syncPendingRecords(_ resolve: @escaping RCTPromiseResolveBlock,
                                 rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["synced": 0, "failed": 0, "remaining": 0])
  }
  
  @objc func clearAllData(_ resolve: @escaping RCTPromiseResolveBlock,
                           rejecter reject: @escaping RCTPromiseRejectBlock) {
    resolve(["success": true, "message": "All data cleared"])
  }
}
