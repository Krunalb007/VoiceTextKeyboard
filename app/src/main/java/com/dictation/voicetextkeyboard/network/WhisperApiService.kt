package com.dictation.voicetextkeyboard.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface WhisperApiService {
    @Multipart
    @POST("audio/transcriptions")
    fun uploadAudio(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("temperature") temperature: RequestBody,
        @Part("response_format") responseFormat: RequestBody,
        @Part("language") language: RequestBody
    ): Call<WhisperResponse>
}
