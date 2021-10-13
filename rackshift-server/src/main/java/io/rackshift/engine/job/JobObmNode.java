package io.rackshift.engine.job;

import com.alibaba.fastjson.JSONObject;
import io.rackshift.model.RSException;
import io.rackshift.mybatis.domain.OutBand;
import io.rackshift.mybatis.mapper.TaskMapper;
import io.rackshift.service.OutBandService;
import io.rackshift.utils.IPMIUtil;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;

import java.util.Map;

@Jobs("Job.Obm.Node")
public class JobObmNode extends BaseJob {
    public JobObmNode() {

    }

    /**
     * @param taskId             task 表的 id
     * @param instanceId         task表中 graphObjct 字段每一个具体子任务的 id
     * @param context            task表中 graphObjct 字段每一个具体子任务的 json 对象
     * @param taskMapper
     * @param applicationContext
     * @param rabbitTemplate
     */
    public JobObmNode(String taskId, String instanceId, JSONObject context, TaskMapper taskMapper, ApplicationContext applicationContext, RabbitTemplate rabbitTemplate) {
        this.instanceId = instanceId;
        this.taskId = taskId;
        this.context = context;
        this.options = context.getJSONObject("options");
        this._status = context.getString("state");
        this.taskMapper = taskMapper;
        this.task = taskMapper.selectByPrimaryKey(taskId);
        this.bareMetalId = context.getString("bareMetalId");
        this.applicationContext = applicationContext;
        this.job = (Map<String, Class>) applicationContext.getBean("job");
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void run() {
        String action = this.options.getString("action");
        try {
            new OBMService(action).run();
            this.succeeded();
        } catch (Exception e) {
            this.error(e);
        }
    }

    private class OBMService {
        private String action;

        private OBMService(String action) {
            this.action = action;
        }

        private void run() throws Exception {
            OutBandService outBandService = (OutBandService) applicationContext.getBean("outBandService");
            OutBand outBand = outBandService.getByBareMetalId(bareMetalId);
            if (outBand == null) {
                RSException.throwExceptions("no obm info set!");
            }
            IPMIUtil.Account account = IPMIUtil.Account.build(outBand);
            switch (action) {
                case "setBootPxe":
                    IPMIUtil.exeCommand(account, "chassis setbootdev pxe");
                    break;

                case "reboot":
                    IPMIUtil.exeCommand(account, "chassis power off");
                    Thread.sleep(3000);
                    IPMIUtil.exeCommand(account, "chassis power on");
                    break;

                default:
                    break;
            }
        }
    }
}