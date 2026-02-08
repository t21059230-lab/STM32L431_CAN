//#include "serial.h"
#include "hardware.h"
#include "gps.h"
#include "mainh.h"
#include "nav.h"   
#include "fusion.h"
#include "guidance.h"

char used_fusion=0;
unsigned long CntrGps=0;
char LabTestFlag=0;
//extern int GPSProc;
static uint8_t kca_status = UNINIT; // STATUS : not initialized
static uint16_t kca_len=KCA_MAX_PAYLOAD;

unsigned int GpsConnect=0;

//////// GPS data
mat3x1 R_e_gps(0), R_n_gps(0), V_n_gps(0), V_e_gps(0);// GPS Pos and Vel
mat3x1 R_l_gps(0), V_l_gps(0);    			// GPS Pos and Vel
double Gfi=0., Glam=0., Gh=0., GvN=0., GvE=0., GvD=0.;

union {
	unsigned int i;
	unsigned char c[2];
} CRC_MSG;
char crc_idx=0;

static uint8_t ck_kca;
uint8_t KCA_Message_Buffer[KCA_MAX_PAYLOAD]={0};
uint8_t KCA_Message[KCA_MAX_PAYLOAD]={0};
NavData *KCA_Nav=(NavData *)KCA_Message;
static uint8_t KCA_Buffer_Index = 0;

char GPS_Received_Flag = FALSE;
unsigned char used_sat = 0;
unsigned char used_satGlo = 0;
unsigned char All_used_sat=0;
double GPSParseTime = 0.0;
long kca_frame_error = 0, kca_msg_cnt = 0, kca_byte_cnt = 0;
char fTime=1;
unsigned long gFlag=0;
unsigned long tagGPS = 0;
unsigned int  uDelay=0;
unsigned char cntrUsedGPS=0;

///1300C
int cnt_err_Fh_Gh=0,cnt_err_Ffi_Gfi=0,cnt_err_lat=0,cnt_enable_gps=0,cnt_err_gps=0,cnt_stat_err_gps=0;

unsigned int CRC_16_Calc(unsigned char *ptr, int count)
{
	/**
	*
	* \param ptr memory address where data
	* \param count
	*
	* \return calculated CRC for the
	*
	* g(X) = X^16 + X^12 + X^5 + 1 ---> 0x1021
	*
	*/
	unsigned int crc, i;
	unsigned int Temp;

	crc = 0;
	while(--count >= 0)
	{
		//Temp=(int)*ptr++ << 8;
		Temp=(int)*ptr;
		Temp<<=8;
		ptr++;
		crc = crc ^ Temp;
		for(i = 0; i < 8; ++i)
		{
			if(crc & 0x8000)
			{
				crc <<= 1 ;
				crc ^= 0x1021;
			}
			else
				crc <<= 1;
		}
	}
	return (crc & 0xFFFF);
}
//-------------------------------------------------
uint32_t tmp=0;
unsigned char snrok[12]={0,0,0,0,0,0,0,0,0,0,0,0};
uint32_t tmp0=0;

uint32_t tmpGlo=0;
unsigned char snrokGlo[12]={0,0,0,0,0,0,0,0,0,0,0,0};

uint32_t tmp0Glo=0;
//////////////////////////////////////pps
float gpsSampleTime=0.;
float pps_time=0;
float old=0;
float new1=0;
int cgps=0;
float sumalti=0.0 ,avrg_alti=0.0 ;
int32_t  uDelayPPS=0,delta=0;
void KCA_Parse_Message(void)
{
	for (int i=0;i<KCA_MAX_PAYLOAD;++i)
		KCA_Message[i] = KCA_Message_Buffer[i];
	kca_msg_cnt++;
	
	sumalti+=KCA_Nav->Alti;
	       cgps++;
	 //      	 char str6[256];
	 //	sprintf(str6," cgps:%d",cgps);//
	//	 outscreen(5,12,str6);//*/


	    if(cgps==50)
	      {
			 avrg_alti=(sumalti/50);
			 cgps=0;
			 sumalti=0.0;
		  }

//			CntrGps++;
	//outp(32,32);
	if (KCA_Nav->State == 0x00  )
	{
		gFlag=0;
		GPS_Received_Flag = 0;
	}

	if (KCA_Nav->State >= 0x01)
		gFlag++;


	if ((KCA_Nav->State >= 0x01) && (gFlag > 75)) //v2.6
	{
		// Claculate UsedSats
		tmp = KCA_Nav->UseSat;
		tmp0 = KCA_Nav->VisSat;
		used_sat = 0;
		for (int i=0;i<12;i++) snrok[i]=0;//sh

		int snr_idx=0;
		for (;tmp0;)
			{
				if (tmp0&1)
				{
					if (tmp&1)
				    {
					    used_sat++;
						if(snr_idx>11) snr_idx=11;
						snrok[snr_idx]=1;
					}
				snr_idx++;
				}

				tmp >>=1;tmp0>>=1;

			 }

		tmpGlo = KCA_Nav->GLONASSUseSat;
		tmp0Glo = KCA_Nav->GLONASSVisSat;
		used_satGlo = 0;
		for (int i=0;i<12;i++) snrokGlo[i]=0;//sh

		int snr_idxGlo=0;
		for (;tmp0Glo;)
			{
				if (tmp0Glo&1)
				{
					if (tmpGlo&1)
				    {
					    used_satGlo++;
						if(snr_idxGlo>11) snr_idxGlo=11;
						snrokGlo[snr_idxGlo]=1;
					}
				snr_idxGlo++;
				}

				tmpGlo >>=1;tmp0Glo>>=1;

			 }

		All_used_sat=used_sat+used_satGlo;


		/*if ((fabs(Fphif )>(45.*d2r)))
		{
			GPS_Received_Flag = 0;
			gFlag=0;
		}
		else */
		if ((All_used_sat>4)  &&((used_sat>3)||(used_satGlo>3)))
		{
			GPS_Received_Flag = 1;

			tagGPS = CntrIrqCnt - 2*((KCA_Nav->Packdelay+FIXED_DELAY)*0.001 );//CntrIrqCnt - 2 * (used_sat + FIXED_DELAY);

			old=((KCA_Nav->Packdelay+FIXED_DELAY)*0.001 );
			//new1=uDelayPPS;
			//delta=uDelayPPS-old;
/////////////////////////////
			CntrGps++;
		}
		if (fTime && R_e_gps(0,0)>1.)
			fTime=0;

	}
	else
		GPS_Received_Flag = FALSE;
}
float dcomp(unsigned char DP)
{
 if(DP<100) return 0.05*DP;
 else if(DP<150)return (DP-80)*0.25;
 else if(DP<200)return (DP-132.5);
 else return (DP-197.625)*20;

}
//....................................
void KCA_Parse_Character(uint8_t c)

{
	//	if (kca_status < KCA_GOT_PAYLOAD) {
	//		ck_kca += c;
	//	}
	kca_byte_cnt++;
	switch (kca_status) 
	{
	case UNINIT:
		if (c == KCA_SYNC1)
			kca_status++;
		break;
	case IGOT_SYNC1:
		if (c != KCA_SYNC2)
			goto error;
		kca_status++;
		break;
	case IGOT_SYNC2:
		KCA_Buffer_Index = 0;
		KCA_Message_Buffer[KCA_Buffer_Index] = c;
		KCA_Buffer_Index++;
		kca_status++;
		break;
	case IGOT_TYPE:
		KCA_Message_Buffer[KCA_Buffer_Index] = c;
		KCA_Buffer_Index++;
		if (KCA_Buffer_Index >= kca_len) 
		{
			CRC_MSG.i = CRC_16_Calc(KCA_Message_Buffer,160);
			kca_status++;
			crc_idx=0;
		}
		break;
	case IGOT_PAYLOAD:
		//		outp(32,32);
		//		c=c;
		if (crc_idx == 0)
			if (c != CRC_MSG.c[0])
				goto error;
			else
				crc_idx++;
		else
			if (c != CRC_MSG.c[1])
				goto error;
			else
			{
				KCA_Parse_Message();
				GpsConnect++;
				goto restart;
			}
	}
	return;
error:
	++kca_frame_error;
restart:
	kca_status = UNINIT;
	kca_byte_cnt = 0;
	return;
}


char AnalyzeGPS()
{
	int i;
	char Step = 1;

	Gfi = R_n_gps(0,0) = KCA_Nav->Latt*M_PI/180.;//930904
	Glam= R_n_gps(1,0) = KCA_Nav->Long*M_PI/180.;//930904
	Gh  = R_n_gps(2,0) = KCA_Nav->Alti;
	R_e_gps(0,0) = KCA_Nav->X;
	R_e_gps(1,0) = KCA_Nav->Y;
	R_e_gps(2,0) = KCA_Nav->Z;
	V_e_gps(0,0) = KCA_Nav->VxposPro;
	V_e_gps(1,0) = KCA_Nav->VyposPro;
	V_e_gps(2,0) = KCA_Nav->VzposPro;

//	s2fi    = sin(R_n_gps(0,0))*sin(R_n_gps(0,0));
//	h       = R_n_gps(2,0);

	mat3x3 C_e_n = Rot_e_n(R_n_gps(0,0), R_n_gps(1,0));
	R_l_gps = C_e_l * (R_e_gps - R0_E);
	V_l_gps = C_e_l * V_e_gps;
	V_n_gps = C_e_n * V_e_gps;

	Gfi=R_n_gps(0,0); Glam=R_n_gps(1,0); Gh=R_n_gps(2,0);
	GvN=V_n_gps(0,0); GvE=V_n_gps(1,0); GvD=V_n_gps(2,0);

	uDelay = ((signed)(AbsIrqCnt-tagGPS));
	if (t<0.01)
		uDelay=0;

	GPSParseTime =  uDelay * .0005;


	 unsigned char SNRGPS[12];
	 unsigned char SNRGLO[12];
	 unsigned char SNGPmax=0;
	 unsigned char SNGLmax=0;
	 unsigned char SNRmax=0;
	 float CalSNmax=0;
	 double sigmaPosi = rx;

	 for (i=0;i<12;i++)
	 {
		  (snrok[i]   >0) ?(SNRGPS[i]=KCA_Nav->SNR[i]       ):(SNRGPS[i]=0);
	      (snrokGlo[i]>0) ?(SNRGLO[i]=KCA_Nav->GLONASSSNR[i]):(SNRGLO[i]=0);
	 }
	 SNGPmax=SNRGPS[0];
	 SNGLmax=SNRGLO[0];
	 for(i=1;i<12;i++)
	 {
	   if(SNRGPS[i]>SNGPmax)
		SNGPmax=SNRGPS[i];
	   if(SNRGLO[i]>SNGLmax)
		SNGLmax=SNRGLO[i];
	 }

	 if(used_sat==0)
		 SNRmax=SNGLmax;
	 else if( used_satGlo==0)
	 	 SNRmax=SNGPmax;
	 else if(used_sat>=1)
	      SNRmax=SNGPmax;
     else
	     SNRmax=SNGLmax;///////////?
	 CalSNmax=((SNRmax-10)*-0.1)+4;


	//////////////////////////
	if (All_used_sat==0)        //All_used_sat<5
	   Step = 0;
    else
       sigmaPosi=dcomp(KCA_Nav->GDOP)*(2.+0.25*(pow(10,CalSNmax)));//gdop

	sigmaPosi=sigmaPosi*sigmaPosi;
	if (sigmaPosi>196.)     //20
	{
		rx =sigmaPosi;         //10
		Step = 0;
		//gFlag = 0;
	}
	else if (sigmaPosi>0.1)
		rx =sigmaPosi;   //4
	else
	{
		rx = 200.;
		Step = 0;
		//gFlag = 0;
	}
	/**/
	if(LabTestFlag)
	{
		rx = 36.0;
		R_l_gps = r_l_ins;  //v3zar
		R_e_gps = R0_E + ~C_e_l * R_l_gps;
		R_n_gps = cart2nav(R_e_gps);
		Gfi = R_n_gps(0,0); Glam = R_n_gps(1,0); Gh = R_n_gps(2,0);
		V_l_gps = v_l_ins; //v3zar
		V_e_gps = ~C_e_l * V_l_gps;
		GPSParseTime = 0.008;
		C_e_n   = Rot_e_n(R_n_gps(0,0), R_n_gps(1,0));
		V_n_gps = C_e_n * V_e_gps;

		Gfi=R_n_gps(0,0); Glam=R_n_gps(1,0); Gh=R_n_gps(2,0);
		GvN=V_n_gps(0,0); GvE=V_n_gps(1,0); GvD=V_n_gps(2,0);
	}
	/**/

	R(0,0) =0.9 * rx; R(1,1) =0.6* rx; R(2,2) = 4.*rx;
	R(0,1) = R(0,2) = R(1,0) = R(1,2) = R(2,0) = R(2,1) = 0.0*rx;
	
//n5n
/* 
if (t - Time_Gps < 0.1) 
  {
    if (1000.0 < fabs(Fh - Gh)) 
	{
      Step = 0;
     cnt_err_Fh_Gh = cnt_err_Fh_Gh + 1;
    }
  }
  if (t - Time_Gps < 0.1) 
  {
    if (1000.0 < fabs(Ffi - Gfi) * 108000.0 * 57.3) 
	{
      Step = 0;
      cnt_err_Ffi_Gfi = cnt_err_Ffi_Gfi + 1;
    }
  }
  if (t - Time_Gps < 0.1) 
  {
    if (1000.0 < fabs(Flam - Glam) * 108000.0 * 57.3) 
	{
      Step = 0;
      cnt_err_lat = cnt_err_lat + 1;
    }
  }
 
  if (KCA_Nav->State == 0) 
  {
    Step = 0;
    cnt_enable_gps = cnt_enable_gps + 1;
  }
  cnt_stat_err_gps = 1;
  double lVar10 = (t - Time_Gps) *(t - Time_Gps) *(t - Time_Gps) * 0.01 + 3.0 * sigmaPosi + (t - Time_Gps) *(t - Time_Gps) * 0.03;
  if(lVar10 <= fabs((Gh - FvD * GPSParseTime) - Fh) && (t - Time_Gps) <=15)
	{
		cnt_stat_err_gps = 0;
		cnt_err_gps = cnt_err_gps + 1;
    }//*/
  return Step;
}//

