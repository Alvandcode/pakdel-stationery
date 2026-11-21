package com.pakdel.manager.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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

private const val MODE_CREATE = "create"
private const val MODE_JOIN = "join"

@Composable
fun OnboardingScreen(
    onBack: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    var fullName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var storeName by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(MODE_CREATE) }
    var loading by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "ساخت حساب",
                style = MaterialTheme.typography.headlineMedium,
                color = PakdelBlue,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "با ساخت حساب، فروشگاه خود را راه بیندازید یا با کد دعوت به فروشگاه خود بپیوندید.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            PakdelTextField(
                value = fullName,
                onValueChange = { fullName = it; error = null },
                label = "نام و نام خانوادگی",
                enabled = !loading,
            )
            Spacer(Modifier.height(12.dp))
            PakdelTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = "ایمیل",
                placeholder = "name@example.com",
                keyboardType = KeyboardType.Email,
                enabled = !loading,
            )
            Spacer(Modifier.height(12.dp))
            PakdelTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "رمز عبور (حداقل ۶ کاراکتر)",
                visualTransformation = PasswordVisualTransformation(),
                keyboardType = KeyboardType.Password,
                enabled = !loading,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "نوع شروع کار",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == MODE_CREATE, enabled = !loading) {
                        mode = MODE_CREATE; error = null
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = mode == MODE_CREATE,
                    onClick = { mode = MODE_CREATE; error = null },
                    enabled = !loading,
                )
                Text("فروشگاه جدید می‌سازم (من مالک می‌شوم)")
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == MODE_JOIN, enabled = !loading) {
                        mode = MODE_JOIN; error = null
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = mode == MODE_JOIN,
                    onClick = { mode = MODE_JOIN; error = null },
                    enabled = !loading,
                )
                Text("کد دعوت فروشگاه را دارم")
            }

            Spacer(Modifier.height(12.dp))
            if (mode == MODE_CREATE) {
                PakdelTextField(
                    value = storeName,
                    onValueChange = { storeName = it; error = null },
                    label = "نام فروشگاه",
                    placeholder = "مثلاً پاکدل",
                    enabled = !loading,
                )
            } else {
                PakdelTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it.uppercase(); error = null },
                    label = "کد دعوت (۸ کاراکتر)",
                    placeholder = "ABCD2345",
                    enabled = !loading,
                )
            }

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

            Spacer(Modifier.height(20.dp))
            val canSubmit = email.isNotBlank() && password.length >= 6 &&
                if (mode == MODE_CREATE) storeName.isNotBlank() else inviteCode.isNotBlank()

            PakdelButton(
                text = "ساخت حساب و ورود",
                onClick = {
                    scope.launch {
                        loading = true
                        error = viewModel.submit(
                            fullName = fullName,
                            email = email,
                            password = password,
                            inviteCode = if (mode == MODE_JOIN) inviteCode else "",
                            storeName = if (mode == MODE_CREATE) storeName else "",
                        )
                        loading = false
                    }
                },
                loading = loading,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack, enabled = !loading) {
                Text("حساب دارم؛ بازگشت به ورود")
            }
        }
    }
}
