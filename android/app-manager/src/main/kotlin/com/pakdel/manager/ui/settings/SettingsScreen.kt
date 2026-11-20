package com.pakdel.manager.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pakdel.core.common.date.JalaliDate
import com.pakdel.core.data.remote.MemberDto
import com.pakdel.core.designsystem.component.ConfirmDialog
import com.pakdel.core.designsystem.component.PakdelButton
import com.pakdel.core.designsystem.component.PakdelOutlinedButton
import com.pakdel.core.designsystem.component.SectionCard
import kotlinx.coroutines.launch

private val roleOptions = listOf(
    "owner" to "مالک فروشگاه",
    "manager" to "مدیر",
    "sales" to "مسئول فروش",
    "cashier" to "صندوق‌دار",
    "warehouse" to "مسئول انبار",
)

private fun roleLabel(role: String): String =
    roleOptions.firstOrNull { it.first == role }?.second ?: role

private fun rpcLabel(rpc: String): String = when (rpc) {
    "rpc_checkout_sale" -> "فاکتور فروش"
    "rpc_cancel_sale" -> "لغو فاکتور"
    "rpc_adjust_stock" -> "اصلاح موجودی"
    "rpc_upsert_product" -> "ثبت/ویرایش محصول"
    "rpc_delete_product" -> "حذف محصول"
    "rpc_upsert_customer" -> "ثبت/ویرایش مشتری"
    else -> rpc
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busySeq by viewModel.busySeq.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val membersLoading by viewModel.membersLoading.collectAsStateWithLifecycle()
    val membersError by viewModel.membersError.collectAsStateWithLifecycle()
    val inviting by viewModel.inviting.collectAsStateWithLifecycle()

    var showLogout by remember { mutableStateOf(false) }
    var roleDialogMember by remember { mutableStateOf<MemberDto?>(null) }
    var showInvite by remember { mutableStateOf(false) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteName by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf("sales") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("تنظیمات", style = MaterialTheme.typography.titleLarge)

            SectionCard(title = "حساب کاربری") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("نام", style = MaterialTheme.typography.bodyMedium)
                    Text(session?.displayName.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("نقش", style = MaterialTheme.typography.bodyMedium)
                    Text(roleLabel(session?.role.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ایمیل", style = MaterialTheme.typography.bodyMedium)
                    Text(session?.email.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard(title = "همگام‌سازی") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("در صف ارسال", style = MaterialTheme.typography.bodyMedium)
                    Text("${syncStatus.pendingCount}", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("تعارض‌ها", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${syncStatus.conflictCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (syncStatus.conflictCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("آخرین همگام‌سازی موفق", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        syncStatus.lastSuccessAt?.let { JalaliDate.fromEpochMillis(it).formatLong() }
                            ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!syncStatus.lastError.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        syncStatus.lastError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                PakdelButton(
                    text = "همگام‌سازی اکنون",
                    onClick = viewModel::syncNow,
                    loading = syncing,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionCard(title = "عملیات نیازمند بررسی (${conflicts.size})") {
                if (conflicts.isEmpty()) {
                    Text(
                        "عملیات معلقی وجود ندارد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "این عملیات در دستگاه باقی مانده و حذف نشده است. پس از اصلاح علت، «تلاش دوباره»" +
                            " را بزنید؛ در غیر این صورت با «رد کردن»، اثرات محلی آن برگشت می‌خورد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                conflicts.forEach { conflict ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(rpcLabel(conflict.rpc), style = MaterialTheme.typography.labelMedium)
                        Text(
                            conflict.lastError ?: "خطای نامشخص",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            JalaliDate.fromEpochMillis(conflict.createdAt).formatLong(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            val busy = busySeq == conflict.seq
                            TextButton(
                                onClick = { viewModel.retryConflict(conflict.seq) },
                                enabled = busySeq == null,
                            ) { Text(if (busy) "در حال ارسال..." else "تلاش دوباره") }
                            TextButton(
                                onClick = { viewModel.discardConflict(conflict.seq) },
                                enabled = busySeq == null,
                            ) {
                                Text(
                                    "رد کردن",
                                    color = MaterialTheme.colorScheme.error,
                                    textDecoration = TextDecoration.Underline,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                }
            }

            if (session?.canManageStaff == true) {
                SectionCard(title = "کارکنان") {
                    if (membersLoading && members.isEmpty()) {
                        Text("در حال دریافت فهرست...", style = MaterialTheme.typography.bodySmall)
                    } else if (members.isEmpty()) {
                        Text(
                            membersError ?: "کارمندی ثبت نشده است.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (membersError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        members.forEach { member ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = member.isSelf.not() && member.isActive) {
                                        roleDialogMember = member
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        member.fullName + if (member.isSelf) " (شما)" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        textDecoration = if (member.isActive) null
                                        else TextDecoration.LineThrough,
                                    )
                                    Text(
                                        roleLabel(member.role) +
                                            if (member.isActive) "" else " • غیرفعال",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!member.isSelf) {
                                    TextButton(onClick = { viewModel.toggleActive(member) }) {
                                        Text(if (member.isActive) "غیرفعال" else "فعال")
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        Spacer(Modifier.height(8.dp))
                        PakdelOutlinedButton(
                            text = "افزودن کارمند",
                            onClick = {
                                inviteEmail = ""
                                inviteName = ""
                                inviteRole = "sales"
                                showInvite = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "ابتدا کاربر را در Supabase → Authentication بسازید یا دعوت کنید، " +
                                "سپس ایمیل او را اینجا اضافه کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard(title = "درباره") {
                Text(
                    "پاکدل استیشنری — نسخه ۱.۰.۰",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "سامانه مدیریت فروشگاه، نسخه آفلاین‌اول با همگام‌سازی امن.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PakdelOutlinedButton(
                text = "خروج از حساب",
                onClick = { showLogout = true },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    roleDialogMember?.let { member ->
        AlertDialog(
            onDismissRequest = { roleDialogMember = null },
            title = { Text("تغییر نقش ${member.fullName}") },
            text = {
                Column {
                    roleOptions.forEach { (code, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    roleDialogMember = null
                                    viewModel.changeRole(member, code)
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (member.role == code) {
                                Text("فعلی", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { roleDialogMember = null }) { Text("بستن") }
            },
        )
    }

    if (showInvite) {
        AlertDialog(
            onDismissRequest = { if (!inviting) showInvite = false },
            title = { Text("افزودن کارمند") },
            text = {
                Column {
                    OutlinedTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it },
                        label = { Text("ایمیل کاربر") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inviteName,
                        onValueChange = { inviteName = it },
                        label = { Text("نام نمایشی (اختیاری)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("نقش", style = MaterialTheme.typography.labelMedium)
                    roleOptions.forEach { (code, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { inviteRole = code }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (inviteRole == code) {
                                Text("انتخاب‌شده", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !inviting,
                    onClick = {
                        viewModel.invite(inviteEmail, inviteRole, inviteName) { showInvite = false }
                    },
                ) {
                    Text(if (inviting) "در حال افزودن..." else "افزودن")
                }
            },
            dismissButton = {
                TextButton(enabled = !inviting, onClick = { showInvite = false }) { Text("انصراف") }
            },
        )
    }

    ConfirmDialog(
        visible = showLogout,
        title = "خروج از حساب",
        message = "برای جلوگیری از حذف فروش آفلاین، خروج فقط وقتی انجام می‌شود که همه عملیات" +
            " همگام شده باشند. داده فروشگاه پس از خروج از این دستگاه پاک می‌شود.",
        confirmLabel = "خروج",
        destructive = true,
        onConfirm = {
            showLogout = false
            scope.launch { viewModel.logout {} }
        },
        onDismiss = { showLogout = false },
    )
}
