#include "mat_def.h"

/************** Definitions for KCA  *****************/
#define UNINIT            0
#define IGOT_SYNC1        1
#define IGOT_SYNC2        2
#define IGOT_TYPE			  3
#define IGOT_LENGTH       3
#define IGOT_PAYLOAD      4
#define IGOT_CHKSUM       4

#define KCA_SYNC1 0x81u
#define KCA_SYNC2 0x7Eu
#define KCA_MAX_PAYLOAD 160

/************************************************************/
#ifndef INC_TYPES_H
#define INC_TYPES_H

/*  typedefs are here  */
//typedef unsigned char		uint8_t;
//typedef   signed char		int8_t;
//typedef unsigned int		uint16_t;
//typedef   signed int		int16_t;
//typedef unsigned long		uint32_t;
//typedef   signed long		int32_t;

#define TRUE 1
#define FALSE 0

#endif

extern float sumalti,avrg_alti;
extern long cont;
/// GPS Variables
extern mat3x1 R_n_gps, R_e_gps, V_e_gps;
extern mat3x1 R_l_gps, V_l_gps; // GPS Pos and Vel
extern double Gfi, Glam, Gh, GvN, GvE, GvD;
extern double GPSParseTime;
extern unsigned char used_sat;
extern unsigned char used_satGlo;
extern unsigned int uDelay, GpsConnect;
extern unsigned long gFlag;
extern unsigned char cntrUsedGPS;
extern unsigned long tagGPS;
extern char GPS_Received_Flag;
extern long kca_frame_error, //framing error occured in GPS
	kca_msg_cnt,     //number of GPS messages received
	kca_byte_cnt;    //bytes received from GPS (resets each 80 byte)

#define FIXED_DELAY 14236
#pragma pack(1)
 struct 
{
	uint8_t  MsgType;
	uint8_t  State;
	int8_t   UTemp;
	uint32_t UTCTime;
	uint32_t VisSat;
	uint32_t UseSat;
	/*float    X,Y,Z;
	float    Latt,Long,Alti;
	float    Vx,Vy,Vz;
	float    DOP;
	unsigned char  SNR[8];
	unsigned short WkNum;
	unsigned short Wknum2;
	unsigned int LocTime;
	unsigned char  Reserved[7];*/
	uint32_t GLONASSVisSat;
	uint32_t GLONASSUseSat;
	float    X,XposPro,Y,YposPro,Z,ZposPro;
	float    Latt,LattposPro,Long,LongposPro,Alti,AltiposPro;
	float    Vx,VxposPro,Vy,VyposPro,Vz,VzposPro;
	float    Ax,Ay,Az;
	uint8_t  SNR[12];
	uint8_t  GLONASSSNR[12];
	uint16_t WkNum;
	uint16_t UTCOffset;
	uint32_t LocTime;
	int32_t  Packdelay;
	uint8_t  GDOP,PDOP,HDOP,VDOP,TDOP;
	//unsigned char ndop;
	// float    DOP;
	uint8_t  Reserved[12];

}typedef NavData;
extern NavData *KCA_Nav;

char AnalyzeGPS();
float dcomp(unsigned char DP);
unsigned int CRC_16_Calc(unsigned char *ptr, int count);
