package com.dssmeet.kiosk.di

import com.dssmeet.kiosk.BuildConfig
import com.dssmeet.kiosk.api.TrustApiService
import com.dssmeet.kiosk.config.AppConfig
import com.dssmeet.kiosk.network.interceptors.AuthInterceptor
import com.dssmeet.kiosk.network.interceptors.EnvironmentInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * SPKI (public-key) pins for the production hosts — leaf + intermediate +
     * ISRG roots, so a normal Let's Encrypt renewal (Caddy reuses the key) and
     * even an intermediate swap keep matching, while a rogue/MITM certificate
     * is rejected. Regenerate on rotation with:
     *   openssl s_client -connect api.dsssolutions.in:443 -showcerts |
     *     openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER |
     *     openssl dgst -sha256 -binary | openssl enc -base64
     * Dev hosts (127.0.0.1 / LAN) are not pinned, so local builds are unaffected.
     */
    private val certificatePinner = CertificatePinner.Builder()
        .add(
            "api.dsssolutions.in",
            "sha256/gEod85TureQf+/Vye3FdGe26m7OcWYnwsKfDwMZhq08=", // leaf
            "sha256/s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=", // intermediate YE2
            "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // ISRG Root YE
            "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="  // ISRG Root X2
        )
        .add(
            "signal.dsssolutions.in",
            "sha256//T/NJOcECru4rfCWDQsANQsQf2chO9ZirBPEjJrHi9I=", // leaf
            "sha256/brzvtCELCIZUo4sD/qPX0ccRtPsd3DY6RfmxpOU9oB4=", // intermediate YE1
            "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=", // ISRG Root YE
            "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="  // ISRG Root X2
        )
        .build()

    /**
     * Debug builds log full request/response bodies; release builds log only
     * the request line (method/URL/status) — headers and bodies (which carry
     * Authorization tokens, PINs, payloads) never reach logcat in release.
     */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.BASIC
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        deviceInterceptor: com.dssmeet.kiosk.network.interceptors.DeviceInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
        environmentInterceptor: EnvironmentInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
        .addInterceptor(environmentInterceptor)
        .addInterceptor(deviceInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(AppConfig.NETWORK_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(AppConfig.NETWORK_TIMEOUT, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(AppConfig.baseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun provideTrustApiService(retrofit: Retrofit): TrustApiService =
        retrofit.create(TrustApiService::class.java)
}
