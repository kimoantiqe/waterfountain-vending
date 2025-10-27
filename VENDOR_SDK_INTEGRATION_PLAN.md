# Vendor SDK Integration Plan

**Date:** October 24, 2025  
**Objective:** Replace our custom SDK implementation with the vendor's proven `SerialPortUtils-release.aar` SDK

---

## Executive Summary

We will integrate the vendor's SDK (`CYVendingMachine` from `SerialPortUtils-release.aar`) to communicate with the vending machine hardware. Our custom SDK will be **removed entirely** and replaced with a thin adapter layer that:
- Wraps vendor's callback-based API into Kotlin coroutines
- Enforces our 48-slot validation rules
- Maintains our clean `Result<T>` based API
- Provides better testability through interfaces

---

## Phase 1: Setup & Dependencies

### 1.1 Move AAR File
**Location:** Move `SerialPortUtils-release.aar` to:
```
waterfountain-vending/app/libs/SerialPortUtils-release.aar
```

### 1.2 Update Gradle Configuration
**File:** `app/build.gradle.kts`

Add AAR dependency:
```kotlin
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    // ... existing dependencies
}
```

Ensure `libs` directory is recognized:
```kotlin
android {
    // ... existing config
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }
}
```

### 1.3 Update AndroidManifest.xml
**File:** `app/src/main/AndroidManifest.xml`

Add required permissions:
```xml
<!-- Vendor SDK Required Permissions -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission 
    android:name="android.permission.WRITE_SETTINGS"
    tools:ignore="ProtectedPermissions" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

---

## Phase 2: Architecture Design

### 2.1 Keep Interface, Replace Implementation

**What stays:**
- ✅ `WaterFountainManager.kt` - High-level manager (public API)
- ✅ Slot validation logic (`SlotValidator.kt`)
- ✅ Domain models (`WaterDispenseResult`, etc.)

**What gets replaced:**
- ❌ Delete entire `hardware/sdk/` package
- ❌ Remove `VendingMachineSDK.kt` interface
- ❌ Remove `VendingMachineSDKImpl.kt`
- ❌ Remove `SerialCommunicator.kt` and implementations
- ❌ Remove `ProtocolFrame.kt`, `VmcCommands.kt`, `VmcResponse.kt`
- ❌ Remove all protocol-related code

**What gets created:**
- ✅ `VendorSDKAdapter.kt` - Wrapper around `CYVendingMachine`
- ✅ `VendorSDKCallbacks.kt` - Callback handlers
- ✅ Updated `WaterFountainManager.kt` - Uses adapter instead of old SDK

### 2.2 New Architecture Diagram

```
┌─────────────────────────────────────────────────┐
│           WaterFountainManager                   │ ← Public API (No changes)
│  - dispenseWater()                              │
│  - initialize()                                 │
│  - shutdown()                                   │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│           VendorSDKAdapter                       │ ← NEW: Thin wrapper
│  - dispenseWater(slot): Result<...>            │
│  - getDeviceId(): Result<String>                │
│  - Converts callbacks → coroutines              │
│  - Enforces slot validation                     │
└────────────────┬────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────┐
│     CYVendingMachine (Vendor SDK)               │ ← Vendor's code
│  - Constructor blocks, opens serial port        │
│  - Callback-based API                           │
│  - Native library: libserial_port.so            │
└─────────────────────────────────────────────────┘
                 │
                 ▼
        ┌───────────────────┐
        │  /dev/ttyS0       │
        │  9600 baud        │
        │  UART Hardware    │
        └───────────────────┘
```

---

## Phase 3: Implementation Details

### 3.1 Slot Validation Strategy

**Decision:** Keep our strict 48-slot validation

**Rationale:**
- Prevents accidental invalid slot access
- Protects hardware from misuse
- Matches physical machine layout (6 rows × 8 slots)
- Vendor SDK is too permissive (allows 1-255)

**Implementation:**
- Keep `SlotValidator.kt` unchanged
- Validate **before** calling vendor SDK
- Throw `IllegalArgumentException` for invalid slots

### 3.2 Callback → Coroutine Conversion

**Challenge:** Vendor SDK uses callbacks, we need suspend functions

**Solution:** Use `suspendCancellableCoroutine`

```kotlin
suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> = 
    suspendCancellableCoroutine { continuation ->
        try {
            // Validate slot
            SlotValidator.validateSlotOrThrow(slot)
            
            // Create vendor SDK instance with callback
            val vendorSDK = CYVendingMachine(slot, 
                object : CYVendingMachine.ShipmentListener {
                    override fun Shipped(status: Int) {
                        // Map status → Result
                        val result = mapStatusToResult(status, slot)
                        continuation.resume(result)
                    }
                }
            )
            
            // Handle cancellation
            continuation.invokeOnCancellation {
                vendorSDK.closeSerialPort()
            }
            
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
```

### 3.3 Status Code Mapping

**Vendor SDK Status Codes:**
```
0 = Initial state
1 = Reset acknowledged
2 = Delivery in progress
3 = Delivery successful (0x01)
4 = Motor failure (0x02)
5 = Optical eye failure (0x03)
6 = Other error
```

**Our Result Mapping:**
```kotlin
private fun mapStatusToResult(
    status: Int, 
    slot: Int, 
    startTime: Long
): Result<WaterDispenseResult> {
    val dispensingTime = System.currentTimeMillis() - startTime
    
    return when (status) {
        3 -> Result.success(
            WaterDispenseResult(
                success = true,
                slot = slot,
                dispensingTimeMs = dispensingTime
            )
        )
        4 -> Result.success(
            WaterDispenseResult(
                success = false,
                slot = slot,
                errorCode = 0x02,
                errorMessage = "Motor failure - Slot $slot mechanism fault",
                dispensingTimeMs = dispensingTime
            )
        )
        5 -> Result.success(
            WaterDispenseResult(
                success = false,
                slot = slot,
                errorCode = 0x03,
                errorMessage = "Optical sensor failure - Detection error",
                dispensingTimeMs = dispensingTime
            )
        )
        6 -> Result.success(
            WaterDispenseResult(
                success = false,
                slot = slot,
                errorMessage = "Unknown hardware error",
                dispensingTimeMs = dispensingTime
            )
        )
        else -> Result.failure(
            IllegalStateException("Unexpected status: $status")
        )
    }
}
```

### 3.4 Thread Safety & Synchronization

**Challenge:** Vendor SDK callbacks fire on background thread

**Solution:**
```kotlin
class VendorSDKAdapter(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    
    // Ensure only one operation at a time
    private val operationMutex = Mutex()
    
    suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> {
        return operationMutex.withLock {
            // Implementation here
        }
    }
}
```

### 3.5 Resource Management

**Lifecycle:**
```kotlin
class VendorSDKAdapter {
    private var currentVendorSDK: CYVendingMachine? = null
    
    suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> {
        try {
            // Create new instance per operation
            val sdk = CYVendingMachine(slot, listener)
            currentVendorSDK = sdk
            
            // Wait for result...
            
        } finally {
            // Always cleanup
            currentVendorSDK?.closeSerialPort()
            currentVendorSDK = null
        }
    }
}
```

---

## Phase 4: Error Handling

### 4.1 Exception Types

**Keep these from our SDK:**
```kotlin
sealed class VendingMachineException(message: String) : Exception(message) {
    class HardwareException(message: String, val errorCode: Byte? = null) : VendingMachineException(message)
    class InvalidSlotException(message: String) : VendingMachineException(message)
    class TimeoutException(message: String) : VendingMachineException(message)
    class PermissionException(message: String) : VendingMachineException(message)
}
```

### 4.2 Timeout Handling

**Problem:** Vendor SDK might hang if hardware doesn't respond

**Solution:**
```kotlin
suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult> {
    return try {
        withTimeout(30_000) { // 30 second timeout
            // Operation here
        }
    } catch (e: TimeoutCancellationException) {
        Result.failure(
            VendingMachineException.TimeoutException(
                "Hardware did not respond within 30 seconds"
            )
        )
    }
}
```

### 4.3 Permission Handling

**Check at initialization:**
```kotlin
suspend fun initialize(): Boolean {
    return try {
        // Check if /dev/ttyS0 exists and is accessible
        val serialPort = File("/dev/ttyS0")
        if (!serialPort.exists()) {
            throw VendingMachineException.HardwareException(
                "Serial port /dev/ttyS0 not found"
            )
        }
        
        if (!serialPort.canRead() || !serialPort.canWrite()) {
            throw VendingMachineException.PermissionException(
                "No permission to access serial port. App may need root/system privileges."
            )
        }
        
        true
    } catch (e: Exception) {
        Log.e(TAG, "Initialization failed", e)
        false
    }
}
```

---

## Phase 5: Testing Strategy

### 5.1 Mock Mode Support

**Keep mock mode for development:**
```kotlin
class WaterFountainManager private constructor(
    context: Context,
    private val useMockMode: Boolean = false
) {
    private val adapter: VendorSDKAdapter? = if (useMockMode) null else VendorSDKAdapter()
    
    suspend fun dispenseWater(): WaterDispenseResult {
        return if (useMockMode) {
            // Simulate success
            delay(2000)
            WaterDispenseResult(success = true, slot = currentSlot, dispensingTimeMs = 2000)
        } else {
            adapter!!.dispenseWater(currentSlot).getOrThrow()
        }
    }
}
```

### 5.2 Unit Testing

**Adapter is testable:**
```kotlin
class VendorSDKAdapterTest {
    @Test
    fun `test slot validation rejects invalid slots`() = runTest {
        val adapter = VendorSDKAdapter()
        
        val result = adapter.dispenseWater(slot = 9) // Invalid
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
```

---

## Phase 6: File Changes

### 6.1 Files to DELETE

```
app/src/main/java/com/waterfountainmachine/app/hardware/sdk/
├── VendingMachineSDK.kt                    ❌ DELETE
├── VendingMachineSDKImpl.kt                ❌ DELETE
├── SerialCommunicator.kt                   ❌ DELETE
├── SerialCommunicatorImpl.kt               ❌ DELETE
├── SerialConfig.kt                         ❌ DELETE
├── ProtocolFrame.kt                        ❌ DELETE
├── ProtocolFrameParser.kt                  ❌ DELETE
├── VmcCommands.kt                          ❌ DELETE
├── VmcCommandBuilder.kt                    ❌ DELETE
├── VmcResponse.kt                          ❌ DELETE
└── VmcException.kt                         ❌ DELETE (will recreate simplified version)
```

### 6.2 Files to KEEP (with modifications)

```
app/src/main/java/com/waterfountainmachine/app/hardware/
├── WaterFountainManager.kt                 ✏️ MODIFY (use adapter)
└── sdk/
    └── SlotValidator.kt                    ✅ KEEP (unchanged)
```

### 6.3 Files to CREATE

```
app/src/main/java/com/waterfountainmachine/app/hardware/sdk/
├── VendorSDKAdapter.kt                     ✨ NEW (main wrapper)
├── VendingMachineException.kt              ✨ NEW (simplified exceptions)
└── VendorSDKCallbackHandler.kt            ✨ NEW (callback utilities)

app/libs/
└── SerialPortUtils-release.aar             ✨ NEW (vendor SDK)
```

### 6.4 Test Files to DELETE

```
app/src/test/java/com/waterfountainmachine/app/hardware/sdk/
├── ProtocolFrameTest.kt                    ❌ DELETE
├── ProtocolFrameParserTest.kt              ❌ DELETE
├── VmcCommandBuilderTest.kt                ❌ DELETE
└── VmcProtocolTest.kt                      ❌ DELETE
```

### 6.5 Test Files to CREATE

```
app/src/test/java/com/waterfountainmachine/app/hardware/sdk/
└── VendorSDKAdapterTest.kt                 ✨ NEW (adapter tests)
```

---

## Phase 7: Implementation Steps (Execution Order)

### Step 1: Prepare Environment
```bash
# 1. Create libs directory
mkdir -p waterfountain-vending/app/libs

# 2. Move AAR file
mv SerialPortUtils-release.aar waterfountain-vending/app/libs/

# 3. Verify native libraries exist in AAR
unzip -l waterfountain-vending/app/libs/SerialPortUtils-release.aar | grep "\.so$"
```

### Step 2: Update Gradle & Manifest
- Update `app/build.gradle.kts` with AAR dependency
- Update `AndroidManifest.xml` with permissions
- Sync Gradle
- Verify vendor classes are accessible

### Step 3: Create New Adapter Layer
1. Create `VendingMachineException.kt`
2. Create `VendorSDKCallbackHandler.kt`
3. Create `VendorSDKAdapter.kt`
4. Implement core methods (dispenseWater, getDeviceId)

### Step 4: Update WaterFountainManager
1. Remove old SDK imports
2. Replace `VendingMachineSDK` with `VendorSDKAdapter`
3. Update initialization logic
4. Update error handling
5. Test compilation

### Step 5: Delete Old SDK
1. Delete all files in `hardware/sdk/` except `SlotValidator.kt`
2. Delete old test files
3. Clean build

### Step 6: Testing
1. Test in mock mode (no hardware)
2. Test with actual hardware
3. Test error scenarios (invalid slots, timeouts)
4. Verify permissions work correctly

### Step 7: Documentation
1. Update README with vendor SDK info
2. Document slot numbering system
3. Add troubleshooting guide
4. Update API documentation

---

## Phase 8: Risk Mitigation

### 8.1 Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Vendor SDK crashes on init | **HIGH** | Keep mock mode, add try-catch in initialization |
| Callbacks never fire | **HIGH** | Add timeout with `withTimeout`, log all callbacks |
| Thread synchronization issues | **MEDIUM** | Use `Mutex`, ensure single operation at a time |
| Native library missing/incompatible | **HIGH** | Verify `.so` files exist, test on target device first |
| Permissions denied | **MEDIUM** | Check permissions before init, show clear error messages |
| Slot numbering mismatch | **LOW** | Keep strict validation, test all 48 slots |

### 8.2 Rollback Plan

**If integration fails:**
1. Revert to previous Git commit
2. Our old SDK code will still be in Git history
3. Can cherry-pick and restore if needed

**Git Strategy:**
```bash
# Before starting, create backup branch
git checkout -b backup-custom-sdk

# Work on main branch
git checkout main

# Commit vendor SDK changes incrementally
git commit -m "Add vendor SDK AAR and dependencies"
git commit -m "Create vendor SDK adapter"
git commit -m "Update WaterFountainManager to use adapter"
git commit -m "Remove old SDK implementation"
```

---

## Phase 9: Performance Considerations

### 9.1 Memory Management
- Vendor SDK creates threads internally
- Ensure proper cleanup with `closeSerialPort()`
- Use `finally` blocks for guaranteed cleanup

### 9.2 Battery Impact
- Serial port communication is low power
- No change expected from old implementation
- Monitor for any background threads not being closed

### 9.3 Initialization Time
- Vendor SDK blocks on constructor
- First dispense may take longer (~100-200ms)
- Consider pre-initializing during app startup

---

## Phase 10: Success Criteria

### 10.1 Functional Requirements
- ✅ Can dispense water from all 48 valid slots
- ✅ Invalid slots (9, 10, 19, etc.) are rejected
- ✅ Error messages are clear and actionable
- ✅ Mock mode still works for development
- ✅ No memory leaks or resource leaks

### 10.2 Non-Functional Requirements
- ✅ Code compiles without errors
- ✅ All tests pass
- ✅ No crashes during normal operation
- ✅ Responds within 30 seconds or times out gracefully
- ✅ Clean Git history with atomic commits

### 10.3 Verification Steps
1. ✅ Build succeeds: `./gradlew assembleDebug`
2. ✅ Tests pass: `./gradlew test`
3. ✅ App installs on device
4. ✅ Can get device ID
5. ✅ Can dispense from slot 1
6. ✅ Can dispense from all 48 slots
7. ✅ Invalid slot (e.g., 9) shows error
8. ✅ Hardware errors are handled gracefully

---

## Phase 11: Timeline Estimate

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| 1. Setup & Dependencies | 15 min | None |
| 2. Create Adapter Layer | 45 min | Phase 1 |
| 3. Update WaterFountainManager | 30 min | Phase 2 |
| 4. Delete Old SDK | 10 min | Phase 3 |
| 5. Testing & Debugging | 60 min | Phase 4 |
| 6. Documentation | 20 min | Phase 5 |

**Total Estimated Time:** ~3 hours

---

## Appendix A: Key Code Snippets

### A.1 VendorSDKAdapter Interface (Preview)

```kotlin
/**
 * Adapter for vendor's CYVendingMachine SDK
 * Converts callback-based API to coroutines with Result<T>
 */
class VendorSDKAdapter(
    private val portPath: String = "/dev/ttyS0",
    private val baudRate: Int = 9600,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val operationMutex = Mutex()
    private var currentOperation: CYVendingMachine? = null

    /**
     * Dispense water from specified slot
     * @param slot Must be one of the 48 valid slots
     * @return Result with dispense outcome
     */
    suspend fun dispenseWater(slot: Int): Result<WaterDispenseResult>

    /**
     * Get device ID from VMC
     * @return 15-character device ID string
     */
    suspend fun getDeviceId(): Result<String>

    /**
     * Cleanup resources
     */
    suspend fun shutdown()
}
```

### A.2 WaterFountainManager Update (Preview)

```kotlin
class WaterFountainManager private constructor(
    private val context: Context,
    private val useMockMode: Boolean = false
) {
    private val adapter: VendorSDKAdapter? = if (!useMockMode) {
        VendorSDKAdapter(
            portPath = "/dev/ttyS0",
            baudRate = 9600
        )
    } else null

    suspend fun dispenseWater(): WaterDispenseResult {
        return if (useMockMode) {
            simulateMockDispense()
        } else {
            adapter!!.dispenseWater(currentSlot)
                .getOrElse { error ->
                    WaterDispenseResult(
                        success = false,
                        slot = currentSlot,
                        errorMessage = error.message
                    )
                }
        }
    }
}
```

---

## Appendix B: Vendor SDK Classes Reference

### B.1 CYVendingMachine Constructors

```java
// Get device ID only
CYVendingMachine(IDListener idListener)
CYVendingMachine(String port, int baudrate, IDListener idListener)

// Dispense operation
CYVendingMachine(int lineNum, ShipmentListener shipmentListener)
CYVendingMachine(int lineNum, String port, int baudrate, ShipmentListener listener)
```

### B.2 Listener Interfaces

```java
interface ShipmentListener {
    void Shipped(int status); // 0-6 status codes
}

interface IDListener {
    void getID(String deviceId); // 15-char string
}
```

### B.3 Methods

```java
void closeSerialPort() throws IOException;
```

---

## Appendix C: Configuration

### C.1 Serial Port Configuration
- **Path:** `/dev/ttyS0` (UART0)
- **Baud Rate:** 9600 bps
- **Data Bits:** 8
- **Stop Bits:** 1
- **Parity:** None
- **Flow Control:** None

### C.2 Valid Slots (48 total)
```
Row 1: 01, 02, 03, 04, 05, 06, 07, 08
Row 2: 11, 12, 13, 14, 15, 16, 17, 18
Row 3: 21, 22, 23, 24, 25, 26, 27, 28
Row 4: 31, 32, 33, 34, 35, 36, 37, 38
Row 5: 41, 42, 43, 44, 45, 46, 47, 48
Row 6: 51, 52, 53, 54, 55, 56, 57, 58
```

---

## Sign-Off

**Plan Created:** October 24, 2025  
**Reviewed By:** [Pending]  
**Approved By:** [Pending]  
**Implementation Start:** [Pending approval]

---

**END OF PLAN**
