package com.tianji.learning.mq.msg;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author baize
 * @version 1.0
 * @date 2023/9/18 21:32
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class SignInMessage {
    private Long userId;
    private Integer points;
}
