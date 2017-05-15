/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.service.euicc;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Result of a {@link EuiccService#onSwitchToSubscription} operation.
 * @hide
 *
 * TODO(b/35851809): Make this a SystemApi.
 */
public final class SwitchResult implements Parcelable {

    public static final Creator<SwitchResult> CREATOR = new Creator<SwitchResult>() {
        @Override
        public SwitchResult createFromParcel(Parcel in) {
            return new SwitchResult(in);
        }

        @Override
        public SwitchResult[] newArray(int size) {
            return new SwitchResult[size];
        }
    };

    /** @hide */
    @IntDef({
            RESULT_OK,
            RESULT_GENERIC_ERROR,
            RESULT_MUST_DEACTIVATE_SIM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}

    public static final int RESULT_OK = 0;
    public static final int RESULT_GENERIC_ERROR = 1;
    public static final int RESULT_MUST_DEACTIVATE_SIM = 2;

    /** Result of the operation - one of the RESULT_* constants. */
    public final @ResultCode int result;

    /** Implementation-defined detailed error code in case of a failure not covered here. */
    public final int detailedCode;

    private SwitchResult(int result, int detailedCode) {
        this.result = result;
        this.detailedCode = detailedCode;
    }

    private SwitchResult(Parcel in) {
        this.result = in.readInt();
        this.detailedCode = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeInt(detailedCode);
    }

    /** Return a result indicating that the switch was successful. */
    public static SwitchResult success() {
        return new SwitchResult(RESULT_OK, 0);
    }

    /**
     * Return a result indicating that an active SIM must be deactivated to perform the operation.
     */
    public static SwitchResult mustDeactivateSim() {
        return new SwitchResult(RESULT_MUST_DEACTIVATE_SIM, 0);
    }

    /**
     * Return a result indicating that an error occurred for which no other more specific error
     * code has been defined.
     *
     * @param detailedCode an implemenation-defined detailed error code for debugging purposes.
     */
    public static SwitchResult genericError(int detailedCode) {
        return new SwitchResult(RESULT_GENERIC_ERROR, detailedCode);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}