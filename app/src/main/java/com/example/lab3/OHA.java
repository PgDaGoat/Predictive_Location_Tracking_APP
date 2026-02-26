package com.example.lab3;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.Objects;

public class OHA {
    int quant = 10;
    int threshold = 25;
    int time_threshold = 20;
//    static class align_y2b{
//        float delta_m;
//        long cnt;
//    }
    OHA(){}

    private float weightAvgAnglefromAtan2(float angle1, float angle2, float cnt1, float cnt2){
        return (float) Math.toDegrees(Math.atan2(Math.sin(Math.toRadians(angle1))*cnt1 +
                Math.sin(Math.toRadians(angle2))*cnt2, Math.cos(Math.toRadians(angle1))*cnt1
                + Math.cos(Math.toRadians(angle2))*cnt2));
    }

    // create dictionary with r,p pair as index
    Map<ImmutableList<Integer>, float[]> aligned_rp = Maps.newHashMap();

    // TODO: only when moving, should we update the aligning_rp
    public float query_update(float[] euler_angles, float bearing){
        int r = (int) Math.floor(((euler_angles[0]+360)%360)/quant);
        int p = (int) Math.floor(((euler_angles[1]+360)%360)/quant);
        float y = (euler_angles[2]+360)%360;

        ImmutableList<Integer> key = ImmutableList.of(r, p);
        if(aligned_rp.containsKey(key)){
            if (bearing < 0){
                return (Objects.requireNonNull(aligned_rp.get(key))[0] - y + 360)%360;
            }
            float cur_m = (y + bearing + 360)%360;
            //if last visit time longer than threshold, update aligned_rp
            float delta_m = Objects.requireNonNull(aligned_rp.get(key))[0];
            float cnt = Objects.requireNonNull(aligned_rp.get(key))[1];
            if(cnt <= 500){
                delta_m = weightAvgAnglefromAtan2(delta_m, cur_m, cnt, 1);
            }
            else{
                // slow updating after limits
                delta_m = weightAvgAnglefromAtan2(delta_m, cur_m, cnt, 1);
            }
            aligned_rp.put(key, new float[]{delta_m, cnt+1});
        }
        else{
            if(bearing >= 0){
                float cur_m = (y + bearing + 360)%360;
                aligned_rp.put(key, new float[]{cur_m, 1f});
            }
            else{
                return -1;
            }
        }
        return (Objects.requireNonNull(aligned_rp.get(key))[0] - y + 360)%360;
    }
}
