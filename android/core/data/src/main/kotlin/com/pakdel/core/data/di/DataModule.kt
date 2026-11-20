package com.pakdel.core.data.di

import android.content.Context
import androidx.room.Room
import com.pakdel.core.common.coroutines.DefaultDispatcherProvider
import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.data.local.DatabaseMigrations
import com.pakdel.core.data.local.PakdelDatabase
import com.pakdel.core.data.remote.SupabaseConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PakdelDatabase =
        Room.databaseBuilder(context, PakdelDatabase::class.java, PakdelDatabase.NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()

    @Provides
    fun provideDispatchers(): DispatcherProvider = DefaultDispatcherProvider()

    /**
     * کلاینت Supabase با کلید عمومی (anon).
     *
     * - کلید service_role هرگز در اپ قرار نمی‌گیرد.
     * - پلاگین‌های Auth/Postgrest/Storage در نسخه ۳ به‌صورت خودکار نصب می‌شوند.
     */
    @Provides
    @Singleton
    fun provideSupabaseClient(config: SupabaseConfig): SupabaseClient =
        if (config.isConfigured) {
            createSupabaseClient(
                supabaseUrl = config.url,
                supabaseKey = config.anonKey,
            )
        } else {
            // پیکربندی ناقص: آدرس موقت معتبر تا صفحه راه‌اندازی نمایش داده شود
            // و برنامه با خطای مبهم در زمان تزریق کرش نکند.
            createSupabaseClient(
                supabaseUrl = "https://setup-required.pakdel.invalid",
                supabaseKey = "setup-required",
            )
        }
}
