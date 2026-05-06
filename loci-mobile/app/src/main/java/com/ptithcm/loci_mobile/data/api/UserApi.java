package com.ptithcm.loci_mobile.data.api;

import com.ptithcm.loci_mobile.data.model.user.UserProfile;

import retrofit2.Call;
import retrofit2.http.GET;

public interface UserApi {

    @GET("users/me")
    Call<UserProfile> getMyProfile();
}
