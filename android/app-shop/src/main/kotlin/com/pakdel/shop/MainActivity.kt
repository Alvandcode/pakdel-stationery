package com.pakdel.shop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pakdel.core.data.remote.SupabaseConfig
import com.pakdel.core.designsystem.component.SetupRequiredScreen
import com.pakdel.core.designsystem.theme.PakdelBrand
import com.pakdel.core.designsystem.theme.PakdelTheme
import com.pakdel.shop.ui.home.ShopHomeScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var supabaseConfig: SupabaseConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PakdelTheme(brand = PakdelBrand.Shop) {
                if (supabaseConfig.isConfigured) {
                    ShopHomeScreen()
                } else {
                    SetupRequiredScreen("پاکدل استیشنری — فروشگاه")
                }
            }
        }
    }
}
