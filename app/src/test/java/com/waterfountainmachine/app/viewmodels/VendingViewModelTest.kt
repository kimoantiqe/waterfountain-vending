package com.waterfountainmachine.app.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.hardware.WaterFountainManager
import com.waterfountainmachine.app.hardware.sdk.WaterDispenseResult
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
        
        // Should be in HardwareError state after failed initialization
        val errorState = viewModel.uiState.value as VendingUiState.HardwareError
        assertThat(errorState.message).contains("not connected")
    }

    @Test
    fun `initialization should handle connection exceptions`() = runTest {
        coEvery { mockWaterFountainManager.isConnected() } throws Exception("Connection timeout")
        
        viewModel = VendingViewModel(mockWaterFountainManager, testDispatcher)
        advanceUntilIdle()
        
        // Should be in HardwareError state after exception
        val errorState = viewModel.uiState.value as VendingUiState.HardwareError
        assertThat(errorState.message).contains("Connection error")
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
            assertThat(awaitItem()).isEqualTo(VendingUiState.DispensingComplete)
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
            assertThat(errorState.message).contains("Motor failure")
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
            assertThat(errorState.message).contains("Hardware malfunction")
        }
    }

    @Test
    fun `progress should update during dispensing`() = runTest {
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
            
            // Progress should update multiple times during dispensing
            var lastProgress = 0
            var progressUpdates = 0
            
            while (true) {
                val progress = awaitItem()
                if (progress > lastProgress) {
                    progressUpdates++
                    lastProgress = progress
                }
                if (progress == 100) break
            }
            
            // Should have at least a few progress updates
            assertThat(progressUpdates).isAtLeast(5)
            assertThat(lastProgress).isEqualTo(100)
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
        assertThat(viewModel.uiState.value).isEqualTo(VendingUiState.DispensingComplete)
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
}
