package com.waterfountainmachine.app.viewmodels

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.admin.AdminPinManager
import com.waterfountainmachine.app.config.WaterFountainConfig
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for AdminViewModel
 * 
 * Tests cover:
 * - PIN entry (addDigit, removeLastDigit, clearPin)
 * - PIN validation (success, failure, rate limiting)
 * - Lockout logic (3 attempts, 1-hour lockout, expiry)
 * - State management (all 7 states)
 * - Rate limit persistence
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: AdminViewModel
    private lateinit var mockContext: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Create mock Context
        mockContext = mockk(relaxed = true)
        
        // Mock AdminPinManager static methods (since it's an object)
        mockkObject(AdminPinManager)
        every { AdminPinManager.getRateLimitState(any()) } returns Pair(0, 0L)
        every { AdminPinManager.validatePin(any(), any()) } returns false
        justRun { AdminPinManager.saveRateLimitState(any(), any(), any()) }
        
        // Create ViewModel with mocked context
        viewModel = AdminViewModel(mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(AdminPinManager)
    }

    // ========== PIN Entry Tests ==========

    @Test
    fun `addDigit should add digit to PIN code`() = runTest {
        viewModel.pinDisplay.test {
            assertThat(awaitItem()).isEmpty()
            
            viewModel.addDigit("1")
            assertThat(awaitItem()).isEqualTo("1")
            
            viewModel.addDigit("2")
            assertThat(awaitItem()).isEqualTo("12")
            
            viewModel.addDigit("3")
            assertThat(awaitItem()).isEqualTo("123")
        }
    }

    @Test
    fun `addDigit should not exceed max PIN length`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returns false
        
        // Test PIN display updates
        repeat(7) { i ->
            viewModel.addDigit(i.toString())
        }
        
        // Verify 7 digits were added
        assertThat(viewModel.pinDisplay.value).hasLength(7)
        
        // Add 8th digit - this triggers auto-validation and clears the PIN
        viewModel.addDigit("7")
        
        // Wait for validation to complete
        advanceUntilIdle()
        
        // PIN should be cleared after failed validation
        assertThat(viewModel.pinDisplay.value).isEmpty()
    }

    @Test
    fun `removeLastDigit should remove last digit from PIN`() = runTest {
        viewModel.pinDisplay.test {
            assertThat(awaitItem()).isEmpty()
            
            viewModel.addDigit("1")
            assertThat(awaitItem()).isEqualTo("1")
            
            viewModel.addDigit("2")
            assertThat(awaitItem()).isEqualTo("12")
            
            viewModel.addDigit("3")
            assertThat(awaitItem()).isEqualTo("123")
            
            viewModel.removeLastDigit()
            assertThat(awaitItem()).isEqualTo("12")
            
            viewModel.removeLastDigit()
            assertThat(awaitItem()).isEqualTo("1")
            
            viewModel.removeLastDigit()
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `removeLastDigit should do nothing when PIN is empty`() = runTest {
        viewModel.pinDisplay.test {
            assertThat(awaitItem()).isEmpty()
            
            viewModel.removeLastDigit()
            expectNoEvents()
        }
    }

    @Test
    fun `clearPin should clear all digits`() = runTest {
        viewModel.pinDisplay.test {
            assertThat(awaitItem()).isEmpty()
            
            viewModel.addDigit("1")
            awaitItem()
            viewModel.addDigit("2")
            awaitItem()
            viewModel.addDigit("3")
            assertThat(awaitItem()).isEqualTo("123")
            
            viewModel.clearPin()
            assertThat(awaitItem()).isEmpty()
        }
    }

    // ========== PIN Validation Tests ==========

    @Test
    fun `verifyPin should succeed with correct PIN`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returns true
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(AdminUiState.EnteringPin)
            
            // Add PIN and verify
            viewModel.addDigit("1")
            viewModel.addDigit("2")
            viewModel.addDigit("3")
            viewModel.addDigit("4")
            viewModel.verifyPin()
            
            assertThat(awaitItem()).isEqualTo(AdminUiState.Validating)
            assertThat(awaitItem()).isEqualTo(AdminUiState.Authenticated)
        }
    }

    @Test
    fun `verifyPin should fail with incorrect PIN`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returns false
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(AdminUiState.EnteringPin)
            
            // Add PIN and verify
            viewModel.addDigit("1")
            viewModel.addDigit("2")
            viewModel.addDigit("3")
            viewModel.addDigit("4")
            viewModel.verifyPin()
            
            assertThat(awaitItem()).isEqualTo(AdminUiState.Validating)
            val failureState = awaitItem() as AdminUiState.InvalidPin
            assertThat(failureState.remainingAttempts).isEqualTo(2) // 3 - 1 = 2
        }
    }

    @Test
    fun `verifyPin should clear PIN after validation`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returns false
        
        viewModel.pinDisplay.test {
            assertThat(awaitItem()).isEmpty()
            
            viewModel.addDigit("1")
            awaitItem()
            viewModel.addDigit("2")
            awaitItem()
            viewModel.addDigit("3")
            awaitItem()
            viewModel.addDigit("4")
            assertThat(awaitItem()).isEqualTo("1234")
            
            viewModel.verifyPin()
            advanceUntilIdle()
            
            // PIN should be cleared after validation
            assertThat(expectMostRecentItem()).isEmpty()
        }
    }

    @Test
    fun `verifyPin should increment attempt count on failure`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returns false
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(AdminUiState.EnteringPin)
            
            // First attempt
            viewModel.addDigit("1")
            viewModel.addDigit("2")
            viewModel.addDigit("3")
            viewModel.addDigit("4")
            viewModel.verifyPin()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(AdminUiState.Validating)
            val state1 = awaitItem() as AdminUiState.InvalidPin
            assertThat(state1.remainingAttempts).isEqualTo(2)
            
            // Second attempt
            viewModel.addDigit("1")
            viewModel.addDigit("2")
            viewModel.addDigit("3")
            viewModel.addDigit("4")
            viewModel.verifyPin()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(AdminUiState.Validating)
            val state2 = awaitItem() as AdminUiState.InvalidPin
            assertThat(state2.remainingAttempts).isEqualTo(1)
        }
    }

    @Test
    fun `verifyPin should trigger lockout after max attempts`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returns false
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(AdminUiState.EnteringPin)
            
            // First attempt
            repeat(4) { viewModel.addDigit((it + 1).toString()) }
            viewModel.verifyPin()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(AdminUiState.Validating)
            assertThat(awaitItem()).isInstanceOf(AdminUiState.InvalidPin::class.java)
            
            // Second attempt
            repeat(4) { viewModel.addDigit((it + 1).toString()) }
            viewModel.verifyPin()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(AdminUiState.Validating)
            assertThat(awaitItem()).isInstanceOf(AdminUiState.InvalidPin::class.java)
            
            // Third attempt - should trigger lockout
            repeat(4) { viewModel.addDigit((it + 1).toString()) }
            viewModel.verifyPin()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(AdminUiState.Validating)
            val lockoutState = awaitItem() as AdminUiState.MaxAttemptsReached
            assertThat(lockoutState.lockoutMinutes).isEqualTo(WaterFountainConfig.ADMIN_LOCKOUT_MINUTES)
        }
    }

    // ========== Lockout Tests ==========

    @Test
    fun `checkLockoutStatus should show locked out when in lockout period`() = runTest {
        val lockoutTime = System.currentTimeMillis() + 30 * 60 * 1000L // 30 minutes from now
        every { AdminPinManager.getRateLimitState(any()) } returns Pair(3, lockoutTime)
        
        // Create new ViewModel with lockout state
        viewModel = AdminViewModel(mockContext)
        
        viewModel.uiState.test {
            val state = awaitItem() as AdminUiState.LockedOut
            assertThat(state.remainingMinutes).isAtLeast(29)
            assertThat(state.remainingMinutes).isAtMost(31)
        }
    }

    @Test
    fun `checkLockoutStatus should reset when lockout expired`() = runTest {
        val expiredLockoutTime = System.currentTimeMillis() - 1000L // 1 second ago
        every { AdminPinManager.getRateLimitState(any()) } returns Pair(3, expiredLockoutTime)
        
        // Create new ViewModel with expired lockout
        viewModel = AdminViewModel(mockContext)
        
        viewModel.uiState.test {
            // Should be EnteringPin since lockout expired
            assertThat(awaitItem()).isEqualTo(AdminUiState.EnteringPin)
        }
        
        // Verify rate limit was cleared
        verify { AdminPinManager.saveRateLimitState(any(), 0, 0L) }
    }

    @Test
    fun `rate limit state should be persisted`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returns false
        
        // Make a failed attempt
        repeat(4) { viewModel.addDigit((it + 1).toString()) }
        viewModel.verifyPin()
        
        advanceUntilIdle()
        
        // Verify state was saved with attempt count = 1
        verify { AdminPinManager.saveRateLimitState(any(), 1, any()) }
    }

    // ========== Edge Cases ==========

    @Test
    fun `verifyPin should do nothing when PIN is empty`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(AdminUiState.EnteringPin)
            
            viewModel.verifyPin()
            
            // Should stay in EnteringPin state
            expectNoEvents()
        }
    }

    @Test
    fun `addDigit should only accept numeric characters`() = runTest {
        viewModel.pinDisplay.test {
            assertThat(awaitItem()).isEmpty()
            
            viewModel.addDigit("1")
            assertThat(awaitItem()).isEqualTo("1")
            
            // Try to add non-numeric - should be ignored
            viewModel.addDigit("a")
            expectNoEvents()
            
            viewModel.addDigit("2")
            assertThat(awaitItem()).isEqualTo("12")
        }
    }

    @Test
    fun `critical state should be true during validation`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returns true
        
        viewModel.isInCriticalState.test {
            assertThat(awaitItem()).isFalse()
            
            repeat(4) { viewModel.addDigit((it + 1).toString()) }
            viewModel.verifyPin()
            
            advanceUntilIdle()
            // Should enter critical state during validation
            assertThat(awaitItem()).isTrue()
            
            // Should exit critical state after validation
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `successful authentication should reset attempt count`() = runTest {
        every { AdminPinManager.validatePin(any(), any()) } returnsMany listOf(false, true)
        
        // First failed attempt
        repeat(4) { viewModel.addDigit((it + 1).toString()) }
        viewModel.verifyPin()
        advanceUntilIdle()
        
        verify { AdminPinManager.saveRateLimitState(any(), 1, any()) }
        
        // Successful attempt
        repeat(4) { viewModel.addDigit((it + 5).toString()) }  // 5678
        viewModel.verifyPin()
        advanceUntilIdle()
        
        // Should reset to 0 attempts
        verify { AdminPinManager.saveRateLimitState(any(), 0, 0L) }
    }
}
