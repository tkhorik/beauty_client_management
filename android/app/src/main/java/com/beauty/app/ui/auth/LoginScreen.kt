package com.beauty.app.ui.auth

import androidx.compose.runtime.saveable.rememberSaveable
import com.beauty.app.ui.i18n.localizedMessage
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.ui.theme.CardSurface
import com.beauty.app.ui.theme.RoseGoldPrimary
import com.beauty.app.ui.theme.TextLight
import com.beauty.app.ui.theme.TextMuted
import com.beauty.app.ui.i18n.LanguageSelector

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by viewModel::loginPassword
    var showPassword by remember { mutableStateOf(false) }

    // Navigate on success
    LaunchedEffect(viewModel.loginState) {
        if (viewModel.loginState is AuthViewModel.LoginState.Success) {
            onLoginSuccess()
        }
    }

    // The AuthViewModel is shared with RegisterScreen, so an error left behind
    // by the other screen must not appear here.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetState() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title
                Text(
                    text = stringResource(com.beauty.app.R.string.aura_beauty_log),
                    color = RoseGoldPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
                Text(
                    text = stringResource(com.beauty.app.R.string.sign_in_to_continue),
                    color = TextMuted,
                    fontSize = 14.sp
                )

                LanguageSelector(accountId = null, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(8.dp))

                // Email field
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(com.beauty.app.R.string.email), color = TextMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RoseGoldPrimary,
                        unfocusedBorderColor = Color(0x33E5B899),
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    )
                )

                // Password field
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(com.beauty.app.R.string.password), color = TextMuted) },
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) stringResource(com.beauty.app.R.string.hide_password) else stringResource(com.beauty.app.R.string.show_password),
                                tint = TextMuted
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = RoseGoldPrimary,
                        unfocusedBorderColor = Color(0x33E5B899),
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    )
                )

                // Error message
                if (viewModel.loginState is AuthViewModel.LoginState.Error) {
                    Text(
                        text = localizedMessage((viewModel.loginState as AuthViewModel.LoginState.Error).message),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }

                // Submit button
                Button(
                    onClick = { viewModel.login(email, password) },
                    enabled = viewModel.loginState !is AuthViewModel.LoginState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RoseGoldPrimary)
                ) {
                    if (viewModel.loginState is AuthViewModel.LoginState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = stringResource(com.beauty.app.R.string.sign_in),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                TextButton(
                    onClick = onNavigateToForgotPassword,
                    enabled = viewModel.loginState !is AuthViewModel.LoginState.Loading
                ) {
                    Text(
                        text = stringResource(com.beauty.app.R.string.forgot_password),
                        color = RoseGoldPrimary,
                        fontSize = 13.sp
                    )
                }

                TextButton(
                    onClick = onNavigateToRegister,
                    enabled = viewModel.loginState !is AuthViewModel.LoginState.Loading
                ) {
                    Text(
                        text = stringResource(com.beauty.app.R.string.first_time_here_create_an_account),
                        color = RoseGoldPrimary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
