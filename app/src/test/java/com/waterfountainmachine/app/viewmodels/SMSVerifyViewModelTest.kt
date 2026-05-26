package com.waterfountainmachine.app.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.waterfountainmachine.app.core.auth.AuthenticationException
import com.waterfountainmachine.app.core.auth.IAuthenticationRepository
import com.waterfountainmachine.app.core.auth.OtpRequestResponse
import com.waterfountainmachine.app.core.auth.OtpVerifyResponse
import com.waterfountainmachine.app.core.utils.UserErrorMessages
import com.waterfountainmachine.app.features.vending.viewmodels.SMSVerifyUiState
import com.waterfountainmachine.app.features.vending.viewmodels.SMSVerifyViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for SMSVerifyViewModel.
 *
 * Timer note: `initialize()` starts a 120-second countdown via `delay(1000)`
 * in a loop. Using `advanceUntilIdle()` after a verify would drain that whole
 * countdown and overwrite the verify result with `OtpExpired`. We therefore
 * advance by a small fixed amount (long enough to drain the verify path's
 * `MIN_LOADING_DISPLAY_TIME_MS = 800` delay) and use `runCurrent()` to flush
 * the launch coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SMSVerifyViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var viewModel: SMSVerifyViewModel
    private lateinit var mockAuthRepository: IAuthenticationRepository
    private val testDispatcher = StandardTestDispatcher()

    // The VM normalizes "2345678900" -> "+12345678900" via PhoneNumberUtils.
    private val phone = "2345678900"
    private val normalizedPhone = "+12345678900"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockAuthRepository = mockk(relaxed = true)
        viewModel = SMSVerifyViewModel(mockAuthRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Drain the verify launch coroutine (and its 800ms loading delay) without
     *  advancing the timer enough to expire the OTP. */
    private fun kotlinx.coroutines.test.TestScope.drainVerify() {
        advanceTimeBy(900)
        runCurrent()
    }

    // ========== OTP entry ==========

    @Test
    fun `addDigit appends digits up to MAX_OTP_LENGTH`() = runTest {
        repeat(6) { viewModel.addDigit(it.toString()) }
        assertThat(viewModel.otpCode.value).isEqualTo("012345")

        viewModel.addDigit("9")
        assertThat(viewModel.otpCode.value).isEqualTo("012345")
    }

    @Test
    fun `addDigit transitions to OtpCompleted on 6th digit`() = runTest {
        repeat(5) { viewModel.addDigit("1") }
        assertThat(viewModel.uiState.value).isInstanceOf(SMSVerifyUiState.EnteringOtp::class.java)

        viewModel.addDigit("1")
        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(SMSVerifyUiState.OtpCompleted::class.java)
        assertThat((state as SMSVerifyUiState.OtpCompleted).attemptNumber).isEqualTo(1)
    }

    @Test
    fun `deleteDigit removes last digit and does nothing when empty`() = runTest {
        viewModel.addDigit("1")
        viewModel.addDigit("2")
        viewModel.deleteDigit()
        assertThat(viewModel.otpCode.value).isEqualTo("1")

        viewModel.deleteDigit()
        viewModel.deleteDigit()
        assertThat(viewModel.otpCode.value).isEmpty()
    }

    @Test
    fun `clearOtp wipes the entered code`() = runTest {
        repeat(4) { viewModel.addDigit("9") }
        viewModel.clearOtp()
        assertThat(viewModel.otpCode.value).isEmpty()
    }

    // ========== initialize ==========

    @Test
    fun `initialize stores phone number and starts timer`() = runTest {
        viewModel.initialize(normalizedPhone)
        assertThat(viewModel.phoneNumber.value).isEqualTo(normalizedPhone)
        assertThat(viewModel.otpTimeRemaining.value).isEqualTo(120)
    }

    // ========== verifyOtp -- guard rails ==========

    @Test
    fun `verifyOtp with incomplete code transitions to IncompleteOtp without calling repo`() = runTest {
        viewModel.initialize(normalizedPhone)
        repeat(3) { viewModel.addDigit("1") }

        viewModel.verifyOtp()
        runCurrent()

        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.IncompleteOtp)
        coVerify(exactly = 0) { mockAuthRepository.verifyOtp(any(), any()) }
    }

    @Test
    fun `verifyOtp debounces a repeated call within 1 second`() = runTest {
        viewModel.initialize(normalizedPhone)
        repeat(6) { viewModel.addDigit("1") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.success(OtpVerifyResponse(success = true, vendsRemainingToday = 2))

        viewModel.verifyOtp()
        viewModel.verifyOtp() // second call lands inside the 1s debounce window
        drainVerify()

        coVerify(exactly = 1) { mockAuthRepository.verifyOtp(normalizedPhone, "111111") }
    }

    // ========== verifyOtp -- success paths ==========

    @Test
    fun `verifyOtp success with remaining vends transitions to VerificationSuccess`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("2") }
        coEvery { mockAuthRepository.verifyOtp(normalizedPhone, "222222") } returns
            Result.success(
                OtpVerifyResponse(
                    success = true,
                    message = "ok",
                    sessionToken = "tok",
                    dailyVendLimit = 2,
                    vendsUsedToday = 0,
                    vendsRemainingToday = 2
                )
            )

        viewModel.verifyOtp()
        drainVerify()

        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.VerificationSuccess)
        assertThat(viewModel.isInCriticalState.value).isFalse()
    }

    @Test
    fun `verifyOtp success with zero remaining vends transitions to DailyLimitReached`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("3") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.success(
                OtpVerifyResponse(
                    success = true,
                    dailyVendLimit = 2,
                    vendsUsedToday = 2,
                    vendsRemainingToday = 0
                )
            )

        viewModel.verifyOtp()
        drainVerify()

        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.DailyLimitReached)
    }

    // ========== verifyOtp -- failure paths ==========

    @Test
    fun `verifyOtp invalid otp under retry cap shows IncorrectOtp with attemptsRemaining`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("4") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.failure(AuthenticationException.InvalidOtpError("invalid code"))

        viewModel.verifyOtp()
        drainVerify()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(SMSVerifyUiState.IncorrectOtp::class.java)
        // MAX_RETRY_ATTEMPTS=2; 1 used -> 2 remaining (MAX_RETRY_ATTEMPTS+1-1)
        assertThat((state as SMSVerifyUiState.IncorrectOtp).attemptsRemaining).isEqualTo(2)
    }

    @Test
    fun `verifyOtp invalid otp on third attempt shows TOO_MANY_OTP_ATTEMPTS`() = runTest {
        // Inject a manual clock so the 1s debounce window is fully deterministic
        // — no Thread.sleep, no wall-clock flake under CI load. Start well above
        // 0 so the very first verifyOtp() is not auto-debounced (lastVerifyTime
        // starts at 0 inside the VM).
        var fakeNow = 10_000L
        viewModel = SMSVerifyViewModel(mockAuthRepository).also { it.clock = { fakeNow } }
        viewModel.initialize(phone)

        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.failure(AuthenticationException.InvalidOtpError("invalid"))

        repeat(3) { attempt ->
            viewModel.clearOtp()
            repeat(6) { viewModel.addDigit((attempt + 1).toString()) }
            viewModel.verifyOtp()
            advanceTimeBy(1_000) // drain the 800ms loading delay
            runCurrent()
            fakeNow += 1_001     // step past the 1s debounce window
        }

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(SMSVerifyUiState.Error::class.java)
        assertThat((state as SMSVerifyUiState.Error).message)
            .isEqualTo(UserErrorMessages.TOO_MANY_OTP_ATTEMPTS)
    }

    @Test
    fun `verifyOtp certificate error maps to GENERIC_ERROR`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("5") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.failure(AuthenticationException.CertificateError("cert"))

        viewModel.verifyOtp()
        drainVerify()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(SMSVerifyUiState.Error::class.java)
        assertThat((state as SMSVerifyUiState.Error).message)
            .isEqualTo(UserErrorMessages.GENERIC_ERROR)
    }

    @Test
    fun `verifyOtp server error maps to SERVICE_UNAVAILABLE`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("6") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.failure(AuthenticationException.ServerError("500"))

        viewModel.verifyOtp()
        drainVerify()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(SMSVerifyUiState.Error::class.java)
        assertThat((state as SMSVerifyUiState.Error).message)
            .isEqualTo(UserErrorMessages.SERVICE_UNAVAILABLE)
    }

    @Test
    fun `verifyOtp network error maps to NETWORK_ERROR`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("7") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.failure(AuthenticationException.NetworkError("offline"))

        viewModel.verifyOtp()
        drainVerify()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(SMSVerifyUiState.Error::class.java)
        assertThat((state as SMSVerifyUiState.Error).message)
            .isEqualTo(UserErrorMessages.NETWORK_ERROR)
    }

    @Test
    fun `verifyOtp unexpected exception thrown from repo maps to GENERIC_ERROR`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("8") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } throws RuntimeException("boom")

        viewModel.verifyOtp()
        drainVerify()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(SMSVerifyUiState.Error::class.java)
        assertThat((state as SMSVerifyUiState.Error).message)
            .isEqualTo(UserErrorMessages.GENERIC_ERROR)
        assertThat(viewModel.isInCriticalState.value).isFalse()
    }

    // ========== resendOtp ==========

    @Test
    fun `resendOtp success resets otp code, attempts, timer, transitions to OtpResent`() = runTest {
        viewModel.initialize(phone)
        repeat(4) { viewModel.addDigit("1") }
        // Tick the timer down so we can verify it gets reset.
        advanceTimeBy(3_500)
        runCurrent()
        assertThat(viewModel.otpTimeRemaining.value).isLessThan(120)

        coEvery { mockAuthRepository.requestOtp(normalizedPhone) } returns
            Result.success(OtpRequestResponse(success = true, message = "sent"))

        viewModel.resendOtp()
        runCurrent()

        assertThat(viewModel.otpCode.value).isEmpty()
        assertThat(viewModel.otpTimeRemaining.value).isEqualTo(120)
        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.OtpResent)
        assertThat(viewModel.isInCriticalState.value).isFalse()
    }

    @Test
    fun `resendOtp failure surfaces user-friendly ResendError`() = runTest {
        viewModel.initialize(phone)
        coEvery { mockAuthRepository.requestOtp(any()) } returns
            Result.failure(AuthenticationException.NetworkError("offline"))

        viewModel.resendOtp()
        runCurrent()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(SMSVerifyUiState.ResendError::class.java)
        assertThat((state as SMSVerifyUiState.ResendError).message)
            .isEqualTo(UserErrorMessages.GENERIC_ERROR)
    }

    // ========== Timer ==========

    @Test
    fun `OTP timer transitions to OtpExpired after timeout`() = runTest {
        viewModel.initialize(phone)
        assertThat(viewModel.otpTimeRemaining.value).isEqualTo(120)

        advanceTimeBy(121_000)
        runCurrent()

        assertThat(viewModel.otpTimeRemaining.value).isEqualTo(0)
        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.OtpExpired)
    }

    /**
     * Regression for the OTP timer race: a verify that completes shortly
     * before the 120s deadline must NOT be overwritten by the timer's
     * `OtpExpired` write. The timer must be cancelled on terminal success.
     */
    @Test
    fun `OTP timer does not overwrite VerificationSuccess once verify succeeds`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("2") }
        coEvery { mockAuthRepository.verifyOtp(normalizedPhone, "222222") } returns
            Result.success(OtpVerifyResponse(success = true, vendsRemainingToday = 1))

        viewModel.verifyOtp()
        drainVerify()
        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.VerificationSuccess)

        // Advance well past the original 120s deadline. With the bug, the
        // timer body would have fired OtpExpired on top of VerificationSuccess.
        advanceTimeBy(180_000)
        runCurrent()

        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.VerificationSuccess)
    }

    /** Same regression but for the DailyLimitReached terminal state. */
    @Test
    fun `OTP timer does not overwrite DailyLimitReached`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("3") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.success(
                OtpVerifyResponse(
                    success = true,
                    dailyVendLimit = 2,
                    vendsUsedToday = 2,
                    vendsRemainingToday = 0
                )
            )

        viewModel.verifyOtp()
        drainVerify()
        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.DailyLimitReached)

        advanceTimeBy(180_000)
        runCurrent()

        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.DailyLimitReached)
    }

    // ========== Misc ==========

    @Test
    fun `resetUiState returns to EnteringOtp`() = runTest {
        viewModel.initialize(phone)
        repeat(6) { viewModel.addDigit("1") }
        viewModel.resetUiState()
        assertThat(viewModel.uiState.value).isEqualTo(SMSVerifyUiState.EnteringOtp)
    }

    @Test
    fun `getAttemptNumber starts at 1 and increments after each failure`() = runTest {
        viewModel.initialize(phone)
        assertThat(viewModel.getAttemptNumber()).isEqualTo(1)

        repeat(6) { viewModel.addDigit("1") }
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.failure(AuthenticationException.InvalidOtpError("nope"))
        viewModel.verifyOtp()
        drainVerify()

        assertThat(viewModel.getAttemptNumber()).isEqualTo(2)
    }

    // ========== Turbine state-flow transition tests ==========
    //
    // These pin the *sequence* of state emissions, not just the terminal
    // value, so regressions that re-order or drop intermediate states
    // (e.g. skipping the Loading hop) are caught.

    @Test
    fun `verifyOtp success emits EnteringOtp -- OtpCompleted -- Verifying -- VerificationSuccess`() =
        runTest {
            coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
                Result.success(OtpVerifyResponse(success = true, sessionToken = "tok"))

            viewModel.initialize(phone)
            runCurrent()

            viewModel.uiState.test {
                assertThat(awaitItem()).isEqualTo(SMSVerifyUiState.EnteringOtp)

                repeat(6) { viewModel.addDigit("1") }
                assertThat(awaitItem()).isInstanceOf(SMSVerifyUiState.OtpCompleted::class.java)

                viewModel.verifyOtp()
                assertThat(awaitItem()).isInstanceOf(SMSVerifyUiState.Verifying::class.java)
                drainVerify()
                assertThat(awaitItem()).isInstanceOf(SMSVerifyUiState.VerificationSuccess::class.java)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `verifyOtp invalid otp emits Verifying then IncorrectOtp`() = runTest {
        coEvery { mockAuthRepository.verifyOtp(any(), any()) } returns
            Result.failure(AuthenticationException.InvalidOtpError("nope"))

        viewModel.initialize(phone)
        runCurrent()
        repeat(6) { viewModel.addDigit("1") }

        viewModel.uiState.test {
            // We subscribed after the OtpCompleted emission; first item is
            // whatever the current value is.
            val first = awaitItem()
            assertThat(first).isInstanceOf(SMSVerifyUiState.OtpCompleted::class.java)

            viewModel.verifyOtp()
            assertThat(awaitItem()).isInstanceOf(SMSVerifyUiState.Verifying::class.java)
            drainVerify()
            assertThat(awaitItem()).isInstanceOf(SMSVerifyUiState.IncorrectOtp::class.java)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
