package com.ptithcm.loci_mobile.data.api;

import com.ptithcm.loci_mobile.data.model.chat.ChatMessage;
import com.ptithcm.loci_mobile.data.model.chat.SendMessageRequest;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface MessageApi {

    @POST("messages/individual/send")
    Call<ChatMessage> sendDirectMessage(@Body SendMessageRequest request);

    @POST("messages/group/send")
    Call<ChatMessage> sendGroupMessage(@Body SendMessageRequest request);
}
