package com.waterfountainmachine.app.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.auth.IAuthenticationRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for SMSViewModel
 * 
 * Tests cover:
 * - Phone number entry (addDigit, removeLastDigit, clearPhoneNumber)
 * - Phone number validation (length check)
 * - OTP request (success, network errors, daily limit)
 * - Phone number formatting (visible, masked)
 * - Phone visibility toggle
 * - Mock phone number (1111111111)
 * - Critical state management
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SMSViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: SMSViewModel
    private lateinit var mockAuthRepository: IAuthenticationRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Create mock repository
        mockAuthRepository = mockk(relaxed = true)
        
        // Create ViewModel with mocked repository
        viewModel = SMSViewModel(mockAuthRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Phone Number Entry Tests ==========

    @Test
    fun `addDigit should add digit to phone number`() = runTest {
        viewModel.phoneNumber.test {
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
    fun `addDigit should not exceed 10 digits`() = runTest {
        // Add 10 digits (max)
        repeat(10) { i ->
            viewModel.addDigit(i.toString())
        }
        
        // Verify we have 10 digits
        assertThat(viewModel.phoneNumber.value).hasLength(10)
        
        // Try to add 11th digit - should be ignored
        viewModel.addDigit("5")
        
        // Should still be 10 digits
        assertThat(viewModel.phoneNumber.value).hasLength(10)
    }

    @Test
    fun `addDigit should only accept numeric characters`() = runTest {
        viewModel.phoneNumber.test {
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
    fun `removeLastDigit should remove last digit from phone number`() = runTest {
        viewModel.phoneNumber.test {
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
    fun `removeLastDigit should do nothing when phone is empty`() = runTest {
        viewModel.phoneNumber.test {
            assertThat(awaitItem()).isEmpty()
            
            viewModel.removeLastDigit()
            expectNoEvents()
        }
    }

    @Test
    fun `clearPhoneNumber should clear all digits`() = runTest {
        viewModel.phoneNumber.test {
            assertThat(awaitItem()).isEmpty()
            
            viewModel.addDigit("1")
            awaitItem()
            viewModel.addDigit("2")
            awaitItem()
            viewModel.addDigit("3")
            assertThat(awaitItem()).isEqualTo("123")
            
            viewModel.clearPhoneNumber()
            assertThat(awaitItem()).isEmpty()
        }
    }

    // ========== Phone Number Formatting Tests ==========

    @Test
    fun `getFormattedPhoneNumber should format phone correctly`() {
        // Add 3 digits
        repeat(3) { viewModel.addDigit(it.toString()) }
        assertThat(viewModel.getFormattedPhoneNumber()).isEqualTo("012")
        
        // Add 3 more (6 total)
        repeat(3) { viewModel.addDigit(it.toString()) }
        assertThat(viewModel.getFormattedPhoneNumber()).isEqualTo("(012) 012")
        
        // Add 4 more (10 total)
        repeat(4) { viewModel.addDigit(it.toString()) }
        assertThat(viewModel.getFormattedPhoneNumber()).isEqualTo("(012) 012-0123")
    }

    @Test
    fun `getMaskedPhoneNumber should mask first 6 digits`() {
        // Add 6 digits
        repeat(6) { viewModel.addDigit(it.toString()) }
        assertThat(viewModel.getMaskedPhoneNumber()).isEqualTo("******")
        
        // Add 2 more (8 total)
        viewModel.addDigit("7")
        viewModel.addDigit("8")
        assertThat(viewModel.getMaskedPhoneNumber()).isEqualTo("(***) ***-78")
        
        // Add 2 more (10 total)
        viewModel.addDigit("9")
        viewModel.addDigit("0")
        assertThat(viewModel.getMaskedPhoneNumber()).isEqualTo("(***) ***-7890")
    }

    @Test
    fun `togglePhoneVisibility should toggle visibility state`() = runTest {
        viewModel.isPhoneVisible.test {
            assertThat(awaitItem()).isFalse()
            
            viewModel.togglePhoneVisibility()
            assertThat(awaitItem()).isTrue()
            
            viewModel.togglePhoneVisibility()
            assertThat(awaitItem()).isFalse()
        }
    }

    // ========== OTP Request Tests ==========

    @Test
    fun `requestOtp should succeed with valid 10-digit phone`() = runTest {
        coEvery { mockAuthRepository.requestOtp(any()) } returns Result.success(
            com.waterfountainmachine.app.auth.OtpRequestResponse(success = true)
        )
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SMSUiState.PhoneEntry)
            
            // Add valid 10-digit phone
            repeat(10) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SMSUiState.RequestingOtp)
            val successState = awaitItem() as SMSUiState.OtpRequestSuccess
            assertThat(successState.phoneNumber).isEqualTo("0123456789")
            assertThat(successState.isPhoneVisible).isFalse()
        }
    }

    @Test
    fun `requestOtp should fail with invalid phone length`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SMSUiState.PhoneEntry)
            
            // Add only 5 digits
            repeat(5) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SMSUiState.InvalidPhoneNumber)
        }
    }

    @Test
    fun `requestOtp should handle network error`() = runTest {
        coEvery { mockAuthRepository.requestOtp(any()) } returns 
            Result.failure(Exception("Network error occurred"))
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SMSUiState.PhoneEntry)
            
            // Add valid 10-digit phone
            repeat(10) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SMSUiState.RequestingOtp)
            val errorState = awaitItem() as SMSUiState.Error
            assertThat(errorState.message).contains("Network error")
        }
    }

    @Test
    fun `requestOtp should handle timeout error`() = runTest {
        coEvery { mockAuthRepository.requestOtp(any()) } returns 
            Result.failure(Exception("Request timeout"))
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SMSUiState.PhoneEntry)
            
            // Add valid 10-digit phone
            repeat(10) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SMSUiState.RequestingOtp)
            val errorState = awaitItem() as SMSUiState.Error
            assertThat(errorState.message).contains("timed out")
        }
    }

    @Test
    fun `requestOtp should handle daily limit error`() = runTest {
        coEvery { mockAuthRepository.requestOtp(any()) } returns 
            Result.failure(Exception("Daily limit reached"))
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SMSUiState.PhoneEntry)
            
            // Add valid 10-digit phone
            repeat(10) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SMSUiState.RequestingOtp)
            assertThat(awaitItem()).isEqualTo(SMSUiState.DailyLimitReached)
        }
    }

    @Test
    fun `requestOtp should detect mock phone number 1111111111`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SMSUiState.PhoneEntry)
            
            // Add mock phone number
            repeat(10) { viewModel.addDigit("1") }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SMSUiState.RequestingOtp)
            assertThat(awaitItem()).isEqualTo(SMSUiState.DailyLimitReached)
        }
        
        // Verify repository was NOT called for mock number
        coVerify(exactly = 0) { mockAuthRepository.requestOtp(any()) }
    }

    @Test
    fun `requestOtp should include phone visibility in success state`() = runTest {
        coEvery { mockAuthRepository.requestOtp(any()) } returns Result.success(
            com.waterfountainmachine.app.auth.OtpRequestResponse(success = true)
        )
        
        // Toggle visibility on
        viewModel.togglePhoneVisibility()
        
        viewModel.uiState.test {
            skipItems(1) // Skip initial PhoneEntry state
            
            // Add valid 10-digit phone
            repeat(10) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(SMSUiState.RequestingOtp)
            val successState = awaitItem() as SMSUiState.OtpRequestSuccess
            assertThat(successState.isPhoneVisible).isTrue()
        }
    }

    @Test
    fun `resetToPhoneEntry should reset to phone entry state`() = runTest {
        coEvery { mockAuthRepository.requestOtp(any()) } returns 
            Result.failure(Exception("Network error"))
        
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(SMSUiState.PhoneEntry)
            
            // Request OTP and get error
            repeat(10) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            advanceUntilIdle()
            skipItems(1) // Skip RequestingOtp
            assertThat(awaitItem()).isInstanceOf(SMSUiState.Error::class.java)
            
            // Reset to phone entry
            viewModel.resetToPhoneEntry()
            assertThat(awaitItem()).isEqualTo(SMSUiState.PhoneEntry)
        }
    }

    @Test
    fun `critical state should be true during OTP request`() = runTest {
        coEvery { mockAuthRepository.requestOtp(any()) } returns Result.success(
            com.waterfountainmachine.app.auth.OtpRequestResponse(success = true)
        )
        
        viewModel.isInCriticalState.test {
            assertThat(awaitItem()).isFalse()
            
            // Add valid 10-digit phone
            repeat(10) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            // Should enter critical state during request
            assertThat(awaitItem()).isTrue()
            
            // Should exit critical state after request
            assertThat(awaitItem()).isFalse()
        }
    }

    @Test
    fun `critical state should exit even on error`() = runTest {
        coEvery { mockAuthRepository.requestOtp(any()) } returns 
            Result.failure(Exception("Network error"))
        
        viewModel.isInCriticalState.test {
            assertThat(awaitItem()).isFalse()
            
            // Add valid 10-digit phone
            repeat(10) { viewModel.addDigit(it.toString()) }
            viewModel.requestOtp()
            
            advanceUntilIdle()
            // Should enter critical state
            assertThat(awaitItem()).isTrue()
            
            // Should exit critical state even on error
            assertThat(awaitItem()).isFalse()
        }
    }
}
