package com.waterfountainmachine.app.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.hardware.WaterFountainManager
import com.waterfountainmachine.app.core.hardware.sdk.WaterDispenseResult
import com.waterfountainmachine.app.core.utils.UserErrorMessages
import com.waterfountainmachine.app.features.vending.viewmodels.VendingViewModel
import com.waterfountainmachine.app.features.vending.viewmodels.VendingUiState
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for VendingViewModel
 * 
 * Tests cover:
 * - Hardware initialization (connected, disconnected, error)
 * - Water dispensing (success, failure, progress)
 * - Error handling (hardware errors, dispensing errors)
 * - State management (all 7 states)
 * - Critical state management
 * - Retry and force continue functionality
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VendingViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: VendingViewModel
    private lateinit var mockWaterFountainManager: WaterFountainManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Create mock WaterFountainManager
        mockWaterFountainManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Hardware Initialization Tests ==========

    @Test
    fun `initialization should succeed when hardware is connected`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Should be in Ready state after successful initialization
        assertThat(viewModel.uiState.value).isEqualTo(VendingUiState.Ready)
    }

    @Test
    fun `initialization should fail when hardware is not connected`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns false
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Should be in HardwareError state with user-friendly message
        // (technical details are logged via AppLog, not surfaced to UI)
        val errorState = viewModel.uiState.value as VendingUiState.HardwareError
        assertThat(errorState.message).isEqualTo(UserErrorMessages.HARDWARE_NOT_READY)
    }

    @Test
    fun `initialization should handle connection exceptions`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } throws Exception("Connection timeout")
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Should be in HardwareError state with user-friendly message
        // (the raw "Connection timeout" is logged via AppLog, not shown to user)
        val errorState = viewModel.uiState.value as VendingUiState.HardwareError
        assertThat(errorState.message).isEqualTo(UserErrorMessages.HARDWARE_NOT_READY)
    }

    // ========== Water Dispensing Tests ==========

    @Test
    fun `startDispensing should succeed when hardware is ready`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } returns WaterDispenseResult(
            success = true,
            slot = 1
        )
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(VendingUiState.Ready)
            
            viewModel.startDispensing()
            advanceUntilIdle()
            
            assertThat(awaitItem()).isEqualTo(VendingUiState.Dispensing)
            val completedState = awaitItem()
            assertThat(completedState).isInstanceOf(VendingUiState.DispensingComplete::class.java)
        }
    }

    @Test
    fun `startDispensing should fail when hardware is not ready`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns false
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Get current state before attempting to dispense
        val currentState = viewModel.uiState.value
        
        viewModel.startDispensing()
        advanceUntilIdle()
        
        // State should not change from HardwareError
        assertThat(viewModel.uiState.value).isEqualTo(currentState)
        
        // Verify dispenseWater was NOT called
        coVerify(exactly = 0) { mockWaterFountainManager.dispenseWater() }
    }

    @Test
    fun `startDispensing should handle hardware errors during dispensing`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } returns WaterDispenseResult(
            success = false,
            slot = 1,
            errorMessage = "Motor failure"
        )
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(VendingUiState.Ready)
            
            viewModel.startDispensing()
            advanceUntilIdle()
            
            assertThat(awaitItem()).isEqualTo(VendingUiState.Dispensing)
            val errorState = awaitItem() as VendingUiState.DispensingError
            // Technical error ("Motor failure") is logged via AppLog;
            // users see a friendly message instead.
            assertThat(errorState.message).isEqualTo(UserErrorMessages.DISPENSING_FAILED)
            assertThat(errorState.slot).isEqualTo(1)
        }
    }

    @Test
    fun `startDispensing should handle exceptions during dispensing`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } throws Exception("Hardware malfunction")
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(VendingUiState.Ready)
            
            viewModel.startDispensing()
            advanceUntilIdle()
            
            assertThat(awaitItem()).isEqualTo(VendingUiState.Dispensing)
            val errorState = awaitItem() as VendingUiState.DispensingError
            // The thrown "Hardware malfunction" is logged via AppLog;
            // users see a friendly message instead.
            assertThat(errorState.message).isEqualTo(UserErrorMessages.DISPENSING_FAILED)
            assertThat(errorState.slot).isEqualTo(-1)
        }
    }

    @Test
    fun `progress should update during dispensing`() = runTest {
        // After the timeout refactor the fake intermediate-progress loop is gone.
        // We now only emit 0 (start) and 100 (success). This test pins that
        // contract so we notice if it ever changes again.
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } returns WaterDispenseResult(
            success = true,
            slot = 1
        )

        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()

        viewModel.progress.test {
            assertThat(awaitItem()).isEqualTo(0)

            viewModel.startDispensing()
            advanceUntilIdle()

            // After success, progress jumps to 100.
            // (Between 0 and 100 there may be a 0 re-emission from the
            // `_progress.value = 0` reset; we drain anything that's not 100.)
            var p = awaitItem()
            while (p != 100) {
                p = awaitItem()
            }
            assertThat(p).isEqualTo(100)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== Critical State Tests ==========

    @Test
    fun `critical state should be true during dispensing`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } returns WaterDispenseResult(
            success = true,
            slot = 1
        )
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        viewModel.isInCriticalState.test {
            assertThat(awaitItem()).isFalse()
            
            viewModel.startDispensing()
            advanceUntilIdle()
            
            // Should enter critical state during dispensing
            assertThat(awaitItem()).isTrue()
            
            // Should exit critical state after dispensing
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `critical state should exit even on dispensing error`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } throws Exception("Hardware error")
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        viewModel.isInCriticalState.test {
            assertThat(awaitItem()).isFalse()
            
            viewModel.startDispensing()
            advanceUntilIdle()
            
            // Should enter critical state
            assertThat(awaitItem()).isTrue()
            
            // Should exit critical state even on error
            assertThat(awaitItem()).isFalse()
        }
    }

    // ========== Animation and Flow Control Tests ==========

    @Test
    fun `onAnimationComplete should transition to Complete state`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Should start in Ready state
        assertThat(viewModel.uiState.value).isEqualTo(VendingUiState.Ready)
        
        viewModel.onAnimationComplete()
        advanceUntilIdle()
        
        // Should transition to Complete
        assertThat(viewModel.uiState.value).isEqualTo(VendingUiState.Complete)
    }

    @Test
    fun `retryConnection should re-initialize hardware`() = runTest {
        // First attempt fails
        coEvery { mockWaterFountainManager.isConnected() } returns false
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Should be in error state
        assertThat(viewModel.uiState.value).isInstanceOf(VendingUiState.HardwareError::class.java)
        
        // Change mock to return success
        coEvery { mockWaterFountainManager.isConnected() } returns true
        
        viewModel.retryConnection()
        advanceUntilIdle()
        
        // Should be in Ready state after retry
        assertThat(viewModel.uiState.value).isEqualTo(VendingUiState.Ready)
        
        // Verify isConnected was called at least twice (initial + retry)
        coVerify(atLeast = 2) { mockWaterFountainManager.isConnected() }
    }

    @Test
    fun `forceContinue should transition to DispensingComplete from error`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns false
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Should be in error state
        assertThat(viewModel.uiState.value).isInstanceOf(VendingUiState.HardwareError::class.java)
        
        viewModel.forceContinue()
        advanceUntilIdle()
        
        // Should transition to DispensingComplete
        assertThat(viewModel.uiState.value).isInstanceOf(VendingUiState.DispensingComplete::class.java)
    }

    @Test
    fun `progress should reset to zero when dispensing starts`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } returns WaterDispenseResult(
            success = true,
            slot = 1
        )
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Progress should start at 0
        assertThat(viewModel.progress.value).isEqualTo(0)
        
        // Start dispensing
        viewModel.startDispensing()
        advanceUntilIdle()
        
        // Progress should end at 100
        assertThat(viewModel.progress.value).isEqualTo(100)
    }

    // ========== Timeout Tests ==========

    /**
     * Regression for the "dispenseWater hangs forever" bug. The ViewModel now
     * caps the hardware call at [VendingViewModel.DISPENSE_TIMEOUT_MS]. If the
     * hardware never returns, we must surface a DispensingError with the
     * sentinel TIMEOUT code instead of leaving the UI stuck on Dispensing.
     */
    @Test
    fun `dispenseWater that exceeds the timeout transitions to DispensingError TIMEOUT`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        // Suspend "forever" — well past the 60s cap.
        coEvery { mockWaterFountainManager.dispenseWater() } coAnswers {
            kotlinx.coroutines.delay(VendingViewModel.DISPENSE_TIMEOUT_MS * 10)
            WaterDispenseResult(success = true, slot = 1)
        }

        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value).isEqualTo(VendingUiState.Ready)

        viewModel.startDispensing()
        // Advance just past the cap.
        advanceTimeBy(VendingViewModel.DISPENSE_TIMEOUT_MS + 100)
        runCurrent()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(VendingUiState.DispensingError::class.java)
        val err = state as VendingUiState.DispensingError
        assertThat(err.errorCode).isEqualTo(VendingViewModel.TIMEOUT_ERROR_CODE)
        assertThat(err.slot).isEqualTo(-1)
        assertThat(err.message).isEqualTo(UserErrorMessages.DISPENSING_FAILED)
        // Critical state must be released even on timeout.
        assertThat(viewModel.isInCriticalState.value).isFalse()
    }

    @Test
    fun `dispenseWater that finishes just under the timeout still succeeds`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } coAnswers {
            kotlinx.coroutines.delay(VendingViewModel.DISPENSE_TIMEOUT_MS - 1_000)
            WaterDispenseResult(success = true, slot = 3, dispensingTimeMs = 59_000L)
        }

        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()

        viewModel.startDispensing()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(VendingUiState.DispensingComplete::class.java)
        assertThat((state as VendingUiState.DispensingComplete).slot).isEqualTo(3)
    }

    // ========== Turbine state-flow transition tests ==========

    @Test
    fun `successful dispense emits Ready -- Dispensing -- DispensingComplete`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } returns WaterDispenseResult(
            success = true,
            slot = 1
        )

        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(VendingUiState.Ready)

            viewModel.startDispensing()
            assertThat(awaitItem()).isInstanceOf(VendingUiState.Dispensing::class.java)

            advanceUntilIdle()
            assertThat(awaitItem()).isInstanceOf(VendingUiState.DispensingComplete::class.java)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `timeout emits Ready -- Dispensing -- DispensingError`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } returns true
        coEvery { mockWaterFountainManager.dispenseWater() } coAnswers {
            kotlinx.coroutines.delay(VendingViewModel.DISPENSE_TIMEOUT_MS * 10)
            WaterDispenseResult(success = true, slot = 1)
        }

        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(VendingUiState.Ready)

            viewModel.startDispensing()
            assertThat(awaitItem()).isInstanceOf(VendingUiState.Dispensing::class.java)

            advanceTimeBy(VendingViewModel.DISPENSE_TIMEOUT_MS + 100)
            runCurrent()
            val err = awaitItem()
            assertThat(err).isInstanceOf(VendingUiState.DispensingError::class.java)
            assertThat((err as VendingUiState.DispensingError).errorCode)
                .isEqualTo(VendingViewModel.TIMEOUT_ERROR_CODE)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
