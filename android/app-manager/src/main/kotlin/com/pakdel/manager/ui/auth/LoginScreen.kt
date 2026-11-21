package com.pakdel.manager.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pakdel.core.designsystem.component.PakdelButton
import com.pakdel.core.designsystem.component.PakdelTextField
import com.pakdel.core.designsystem.theme.PakdelBlue
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    var email by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var password by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var loading by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var error by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var showOnboarding by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (showOnboarding) {
        OnboardingScreen(onBack = { showOnboarding = false })
        return
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "پاکدل استیشنری",
                style = MaterialTheme.typography.headlineLarge,
                color = PakdelBlue,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "سامانه مدیریت فروشگاه",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))

            PakdelTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = "ایمیل",
                placeholder = "manager@pakdel.ir",
                keyboardType = KeyboardType.Email,
                enabled = !loading,
            )
            Spacer(Modifier.height(14.dp))
            PakdelTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "رمز عبور",
                visualTransformation = PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password,
                enabled = !loading,
            )

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            PakdelButton(
                text = "ورود به فروشگاه",
                onClick = {
                    scope.launch {
                        loading = true
                        error = viewModel.signIn(email, password)
                        loading = false
                    }
                },
                loading = loading,
                enabled = email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showOnboarding = true },
                enabled = !loading,
            ) {
                Text("حساب ندارید؟ ساخت حساب / پیوستن با کد دعوت")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "دسترسی هر کاربر بر اساس نقش تعریف‌شده در سرور تعیین می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
