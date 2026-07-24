package com.beauty.app.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beauty.app.data.api.AuthRequest
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.local.TokenStore
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch

class AuthViewModel(
    private val api: BeautyApi,
    private val tokenStore: TokenStore
) : ViewModel() {

    sealed interface LoginState {
        object Idle : LoginState
        object Loading : LoginState
        object Success : LoginState
        data class Error(val message: String) : LoginState
    }

    var loginState: LoginState by mutableStateOf(LoginState.Idle)
        private set

    fun login(email: String, password: String) {
        viewModelScope.launch {
            loginState = LoginState.Loading
            loginState = try {
                val response = api.login(AuthRequest(email, password))
                tokenStore.saveToken(response.token)
                LoginState.Success
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.Unauthorized) {
                    LoginState.Error("Invalid email or password.")
                } else {
                    LoginState.Error("Login failed. Please try again.")
                }
            } catch (e: Exception) {
                LoginState.Error("Server could not be reached.")
            }
        }
    }
}
