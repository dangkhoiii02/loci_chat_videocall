package com.ptithcm.loci_mobile.data.model.contact;

import com.google.gson.annotations.SerializedName;

public class FriendshipResponse {
    @SerializedName("status")
    private String status;

    public String getStatus() { return status; }
}
