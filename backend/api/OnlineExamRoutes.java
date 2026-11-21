package backend.api;

import backend.db.OnlineExamDAO;
import backend.model.OnlineExam;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.*;

final class OnlineExamRoutes {
    private static final Gson JSON=new Gson();
    private final OnlineExamDAO exams;
    OnlineExamRoutes(OnlineExamDAO exams){this.exams=exams;}

    void handle(HttpExchange exchange,long user,boolean admin)throws IOException {
        String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
        Object result;int status=200;
        String root=admin?"/admin/online-exams":"/online-exams";
        try {
            if(path.equals(root) && "GET".equals(method))result=exams.list(user,admin);
            else if(admin && path.equals(root) && "POST".equals(method)){
                Map<?,?> body=ApiServer.requestBody(exchange);
                if(!(body.get("questions") instanceof List<?> items))throw new IllegalArgumentException("Questions are required");
                List<OnlineExam.Question> questions=new ArrayList<>();
                for(Object item:items){
                    if(!(item instanceof Map<?,?> q) || !(q.get("options") instanceof List<?> options))throw new IllegalArgumentException("Each question needs options");
                    List<String> texts=new ArrayList<>();for(Object option:options){if(!(option instanceof String value))throw new IllegalArgumentException("Options must be text");texts.add(value);}
                    questions.add(new OnlineExam.Question(text(q,"text"),texts,(int)number(q,"correctIndex",0,3)));
                }
                result=exams.publish(user,new OnlineExam(text(body,"title"),text(body,"courseName"),(int)number(body,"semester",0,12),
                        number(body,"opensAt",0,4102444800L),number(body,"closesAt",0,4102444800L),(int)number(body,"durationMinutes",1,240),questions));status=201;
            } else if(admin && path.matches("/admin/online-exams/[1-9][0-9]*/report") && "GET".equals(method))
                result=exams.report(Long.parseLong(path.split("/")[3]));
            else if(!admin && path.matches("/online-exams/[1-9][0-9]*") && "GET".equals(method))
                result=exams.attempt(user,Long.parseLong(path.split("/")[2]),"view",null);
            else if(!admin && path.matches("/online-exams/[1-9][0-9]*/(start|answers|submit)")){
                String action=path.split("/")[3];
                if(!("answers".equals(action)?"PUT":"POST").equals(method))throw new ApiServer.HttpError(405,"Method not allowed");
                Map<?,?> body=ApiServer.requestBody(exchange);List<Integer> answers=null;
                if(!"start".equals(action)){
                    if(!(body.get("answers") instanceof List<?> items))throw new IllegalArgumentException("Answers are required");
                    answers=new ArrayList<>();for(Object item:items){if(item==null)answers.add(null);else answers.add((int)number(Map.of("answer",item),"answer",0,3));}
                }
                result=exams.attempt(user,Long.parseLong(path.split("/")[2]),"answers".equals(action)?"save":action,answers);
            } else throw new ApiServer.HttpError(404,"Online exam endpoint not found");
        }catch(NoSuchElementException failure){throw new ApiServer.HttpError(404,failure.getMessage());}
        catch(IllegalStateException failure){throw new ApiServer.HttpError(409,failure.getMessage());}
        ApiServer.reply(exchange,status,JSON.toJson(result));
    }
    private static String text(Map<?,?> body,String key){if(!(body.get(key) instanceof String value))throw new IllegalArgumentException(key+" is required");return value;}
    private static long number(Map<?,?> body,String key,long min,long max){
        if(!(body.get(key) instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue()!=Math.rint(number.doubleValue()) || number.doubleValue()<min || number.doubleValue()>max)
            throw new IllegalArgumentException(key+" must be an integer from "+min+" to "+max);
        return number.longValue();
    }
}
